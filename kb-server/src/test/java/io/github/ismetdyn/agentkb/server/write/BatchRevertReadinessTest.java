package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.inBatch;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Why a fact never changes in place (ADR-0022 decisions 22-23, 2026-11-05): {@code kb revert
 * --batch} undoes a batch by deleting what it opened and reopening what it closed (ARCHITECTURE
 * §5). This test does exactly that by hand after a raise, a resolve and a close-and-add; the graph
 * must come back as it was. The revert command itself is G12. updated_at is left out of the
 * comparison: it is the technical sync time and a revert writes a new one.
 */
class BatchRevertReadinessTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-10T09:00:00Z");
    private static final String HOST = "zz-host";
    private static final String SETUP = "batch-setup";
    private static final String UNDONE = "batch-undone";
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

    @Test
    void deleting_what_a_batch_opened_and_reopening_what_it_closed_restores_the_graph() {
        graph.write(
                inBatch(Source.USER_STATED, HOST, T1, SETUP, fact("hostname", "h1.example", PROD)));
        // Waits as a conflict and opens a signal.
        graph.write(inBatch(Source.AGENT, HOST, T2, SETUP, fact("hostname", "h2.example", PROD)));
        graph.write(inBatch(Source.AGENT, HOST, T1, SETUP, fact("ip", IP, "test")));
        graph.write(inBatch(Source.USER, HOST, T1, SETUP, fact("os", "rhel 9", PROD)));
        Map<String, Object> before = graph.state(UPDATED_AT);

        // Raise (rule 1), resolve (decision 23), close and add (rule 2).
        graph.write(inBatch(Source.USER, HOST, T3, UNDONE, fact("ip", IP, "test")));
        graph.write(
                inBatch(
                        Source.USER_STATED,
                        HOST,
                        T3,
                        UNDONE,
                        fact("hostname", "h2.example", PROD)));
        graph.write(inBatch(Source.USER, HOST, T3, UNDONE, fact("os", "rhel 10", PROD)));
        assertThat(graph.state(UPDATED_AT))
                .as("precondition: the batch changed the graph")
                .isNotEqualTo(before);
        assertThat(closedFactCount())
                .as("precondition: ip, both hostnames and os closed")
                .isEqualTo(4L);

        revertByHand(UNDONE);

        assertThat(graph.state(UPDATED_AT)).isEqualTo(before);
    }

    /**
     * The batch a caller gives (a curator batch) is stamped on every node and edge the write opens,
     * the entity included; without it revert would leave records behind.
     */
    @Test
    void a_given_batch_is_stamped_on_everything_the_write_opens() {
        WriteResult result =
                graph.write(inBatch(Source.USER, HOST, T1, SETUP, fact("os", "rhel 9", PROD)));

        assertThat(result.batch()).isEqualTo(SETUP);
        assertThat(graph.rows("MATCH (n) WHERE n.batch IS NULL RETURN n.id AS id", Map.of()))
                .isEmpty();
        assertThat(graph.rows("MATCH ()-[r]->() WHERE r.batch IS NULL RETURN r.id AS id", Map.of()))
                .isEmpty();
    }

    private long closedFactCount() {
        return (Long)
                graph.rows(
                                "MATCH (f:Fact) WHERE f.valid_to IS NOT NULL RETURN count(f) AS n",
                                Map.of())
                        .getFirst()
                        .get("n");
    }

    /** Test helper only: what G12's revert has to do, written straight against the store. */
    private void revertByHand(String batch) {
        graph.store.write(
                tx -> {
                    tx.execute(
                            "MATCH (n {batch: $batch})-[:SUPERSEDES]->(old) WHERE old.batch <> $batch"
                                    + " REMOVE old.valid_to",
                            Map.of("batch", batch));
                    tx.execute("MATCH ()-[r {batch: $batch}]->() DELETE r", Map.of("batch", batch));
                    tx.execute("MATCH (n {batch: $batch}) DETACH DELETE n", Map.of("batch", batch));
                    return null;
                });
    }
}
