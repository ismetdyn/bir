package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One graph with a record for every user edit of ADR-0025 decisions 10-12 to act on (GraphEdits):
 * an open user fact, a value waiting as a conflict next to a user_stated one, a relation, an
 * archived entity, a Stack and one open Proposal of every kind. The ids are the ones the write path
 * returned.
 */
final class EditFixture implements AutoCloseable {

    static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    static final String HOST = "zz-host";
    static final String DATABASE = "zz-db";
    static final String RETIRED = "zz-retired";
    static final String STACK = "stack:zz-stack";
    static final String CURRENT_HOSTNAME = "h1.example";
    static final String WAITING_HOSTNAME = "h2.example";
    static final String DECISION_RULE = "Kbtest services log in JSON.";
    static final String DECISION_CONTENT =
            DECISION_RULE + "\n\nKbtest body: one parser for every log line.";
    // Assumed content form of kind=schema: one facts.yaml entry (MCP-TOOLS kb_propose).
    static final String NEW_KEY = "rack_slot";
    static final String SCHEMA_CONTENT = NEW_KEY + ": {multi: false}";
    static final String REVISION = "Revision";
    static final String REVISES = "REVISES";

    final WriteHarness graph;
    final String osFact;
    final String currentHostnameFact;
    final String waitingFact;
    final String relation;
    final Map<String, String> proposals;

    private EditFixture(
            WriteHarness graph,
            String osFact,
            String currentHostnameFact,
            String waitingFact,
            String relation,
            Map<String, String> proposals) {
        this.graph = graph;
        this.osFact = osFact;
        this.currentHostnameFact = currentHostnameFact;
        this.waitingFact = waitingFact;
        this.relation = relation;
        this.proposals = proposals;
    }

    static EditFixture open(Path dir) throws IOException {
        WriteHarness graph = WriteHarness.open(dir);
        graph.writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(
                                        new Node(
                                                RETIRED,
                                                List.of("Entity", "Server"),
                                                Map.of(
                                                        "aliases",
                                                        List.of(),
                                                        "status",
                                                        "archived",
                                                        "summary",
                                                        "kbtest retired box")),
                                        new Node(STACK, List.of("Stack"), Map.of())),
                                List.of()));
        String os =
                graph.write(request(Source.USER, HOST, T1, fact("os", "rhel 9", PROD)))
                        .added()
                        .getFirst();
        String current =
                graph.write(
                                request(
                                        Source.USER_STATED,
                                        HOST,
                                        T1,
                                        fact("hostname", CURRENT_HOSTNAME, PROD)))
                        .added()
                        .getFirst();
        String waiting =
                graph.write(
                                request(
                                        Source.AGENT,
                                        HOST,
                                        T2,
                                        fact("hostname", WAITING_HOSTNAME, PROD)))
                        .conflicts()
                        .getFirst();
        graph.write(request(Source.USER, DATABASE, T1, fact("engine", "postgresql", PROD)));
        graph.write(relation(Source.USER, HOST, T1, "depends_on", DATABASE));
        String edge =
                (String)
                        graph.rows(
                                        "MATCH (:Entity {id: $from})-[r]->(:Entity {id: $to})"
                                                + " RETURN r.id AS id",
                                        Map.of("from", HOST, "to", DATABASE))
                                .getFirst()
                                .get("id");
        Map<String, String> proposals =
                Map.of(
                        "archive", propose(graph, "archive", HOST, "kbtest archive zz-host"),
                        "decision", propose(graph, "decision", STACK, DECISION_CONTENT),
                        "rule-change", propose(graph, "rule-change", null, "kbtest retire a rule"),
                        "schema", propose(graph, "schema", null, SCHEMA_CONTENT));
        return new EditFixture(graph, os, current, waiting, edge, proposals);
    }

    GraphEdits edits() {
        return graph.writes.edits();
    }

    /** The properties of the node with this id (fact, entity, proposal...). */
    Map<String, Object> node(String id) {
        return GraphRows.props(
                graph.rows("MATCH (n {id: $id}) RETURN properties(n) AS p", Map.of("id", id))
                        .getFirst()
                        .get("p"));
    }

    /** The properties of the edge with this id. */
    Map<String, Object> edge(String id) {
        return GraphRows.props(
                graph.rows("MATCH ()-[r {id: $id}]->() RETURN properties(r) AS p", Map.of("id", id))
                        .getFirst()
                        .get("p"));
    }

    /**
     * {@code state} without the Revision nodes and REVISES edges an in-place edit leaves (ADR-0025
     * decision 15), for tests about what else the edit changed.
     */
    static Map<String, Object> withoutRevisions(Map<String, Object> state) {
        state.values()
                .removeIf(
                        record -> {
                            Object first = ((List<?>) record).getFirst();
                            return first.equals(List.of(REVISION)) || first.equals(REVISES);
                        });
        return state;
    }

    private static String propose(
            WriteHarness graph, String kind, @Nullable String target, String content) {
        return graph.writes.propose(new ProposalRequest(Source.AGENT, kind, target, content, null));
    }

    @Override
    public void close() {
        graph.close();
    }
}
