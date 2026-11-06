package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.inBatch;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relationTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb revert --batch <id>} (ARCHITECTURE §5, ADR-0022 decisions 10 and 22-23, invariant 5):
 * {@code WriteService.revert(batch)} deletes every record the batch opened and reopens every record
 * it closed (found through SUPERSEDES), in one transaction, and gives {@code RevertResult(batch,
 * deleted, reopened)}. Signals are never deleted. Refusals: BatchRevertRefusalTest.
 *
 * <p>updated_at is left out of the comparison: it is the technical sync time and a revert writes a
 * new one on what it reopens (BatchRevertReadinessTest). So is the RevertedBatch node a revert
 * leaves (ADR-0022 decision 42); it is asserted on its own.
 */
class BatchRevertTest {

    static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    static final Instant T3 = Instant.parse("2026-05-10T09:00:00Z");
    static final String HOST = "zz-host";
    private static final String DATABASE = "zz-db";
    private static final String CACHE = "zz-cache";
    private static final String SETUP = "kbtest-batch-setup";
    private static final String UNDONE = "kbtest-batch-undone";
    private static final String IP = "198.51.100.7";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    /**
     * One batch with every kind of change the write path makes: an add (a new fact and a new
     * entity), a close-and-add (rule 2), a raise to a new version (decision 22), a resolve that
     * closes two (decision 23) and a relation add. A Proposal always opens its own batch
     * (ProposalRequest has none), so it is reverted by that batch.
     */
    @Test
    void reverting_a_batch_gives_back_the_graph_as_it_was_before_it() {
        // The readiness test's start: a conflict waits, ip and os are current.
        graph.write(
                inBatch(Source.USER_STATED, HOST, T1, SETUP, fact("hostname", "h1.example", PROD)));
        graph.write(inBatch(Source.AGENT, HOST, T2, SETUP, fact("hostname", "h2.example", PROD)));
        graph.write(inBatch(Source.AGENT, HOST, T1, SETUP, fact("ip", IP, "test")));
        graph.write(inBatch(Source.USER, HOST, T1, SETUP, fact("os", "rhel 9", PROD)));
        graph.write(inBatch(Source.USER, DATABASE, T1, SETUP, fact("engine", "postgresql", PROD)));
        Map<String, Object> before = graph.state(UPDATED_AT);

        graph.write(inBatch(Source.USER, HOST, T3, UNDONE, fact("ip", IP, "test")));
        graph.write(
                inBatch(
                        Source.USER_STATED,
                        HOST,
                        T3,
                        UNDONE,
                        fact("hostname", "h2.example", PROD)));
        graph.write(
                inBatch(
                        Source.USER,
                        HOST,
                        T3,
                        UNDONE,
                        List.of(fact("os", "rhel 10", PROD), fact("version", "2.4", PROD)),
                        List.of(relationTo("depends_on", DATABASE, PROD))));
        graph.write(inBatch(Source.USER, CACHE, T3, UNDONE, fact("engine", "redis", PROD)));
        String proposal =
                graph.writes.propose(
                        new ProposalRequest(
                                Source.AGENT, "archive", HOST, "kbtest archive zz-host", null));
        String proposalBatch = batchOf(proposal);
        long opened =
                (Long)
                        graph.rows(
                                        "MATCH (n {batch: $b}) WITH count(n) AS nodes"
                                                + " MATCH ()-[r {batch: $b}]->()"
                                                + " WITH nodes, count(r) AS edges"
                                                + " RETURN nodes + edges AS n",
                                        Map.of("b", UNDONE))
                                .getFirst()
                                .get("n");
        assertThat(graph.state(UPDATED_AT)).as("precondition").isNotEqualTo(before);
        assertThat(proposalBatch).as("precondition").isNotEqualTo(UNDONE);

        RevertResult proposalRevert = graph.writes.revert(proposalBatch);
        RevertResult result = graph.writes.revert(UNDONE);

        assertThat(graph.stateWithoutRevertMarkers(UPDATED_AT)).isEqualTo(before);
        assertThat(graph.revertMarkers(UNDONE)).as("decision 42").containsExactly(UNDONE);
        assertThat(proposalRevert.deleted()).as("proposal and its ABOUT edge").isEqualTo(2);
        assertThat(result.batch()).isEqualTo(UNDONE);
        assertThat(result.deleted()).isEqualTo(opened);
        assertThat(result.reopened()).as("ip, both hostnames, os").isEqualTo(4);
    }

    /**
     * Invariant 5: a signal is never deleted, also not by a revert of the batch whose write raised
     * it; it stays with its ABOUT edge. The waiting fact itself goes.
     */
    @Test
    void the_signal_a_reverted_write_raised_stays() {
        graph.write(
                inBatch(Source.USER_STATED, HOST, T1, SETUP, fact("hostname", "h1.example", PROD)));
        graph.write(inBatch(Source.AGENT, HOST, T2, UNDONE, fact("hostname", "h2.example", PROD)));
        List<Map<String, Object>> signals = signals();
        assertThat(signals).as("precondition: the conflict raised a signal").hasSize(1);

        graph.writes.revert(UNDONE);

        assertThat(signals()).isEqualTo(signals);
        assertThat(graph.openFacts(HOST, "hostname", PROD))
                .extracting(f -> f.get("value"))
                .containsExactly("h1.example");
        assertThat(
                        graph.rows(
                                "MATCH (:Signal)-[:ABOUT]->(e:Entity {id: $id}) RETURN e.id AS id",
                                Map.of("id", HOST)))
                .hasSize(1);
    }

    private String batchOf(String id) {
        return (String)
                graph.rows("MATCH (n {id: $id}) RETURN n.batch AS batch", Map.of("id", id))
                        .getFirst()
                        .get("batch");
    }

    private List<Map<String, Object>> signals() {
        return graph
                .rows("MATCH (s:Signal) RETURN properties(s) AS s ORDER BY s.id", Map.of())
                .stream()
                .map(row -> GraphRows.props(row.get("s")))
                .toList();
    }
}
