package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * WriteService.importRecords: lossless (invariant 11), no validation, one transaction. Limits from
 * ARCHITECTURE §4: summary 300, text fact 500, body 4,000 characters.
 */
class ImportRecordsTest {

    private static final String AT = "2026-10-01T09:00:00Z";
    private static final String ORPHAN_EDGE_ID = "01ORPHANEDGE00000000000001";
    private static final String MISSING_NODE = "ghost-node";

    @TempDir Path dataDir;

    private EmbeddedNeo4jStore store;
    private WriteService writeService;

    @BeforeEach
    void openStore() {
        store = new EmbeddedNeo4jStore(dataDir);
        // No repo: import validates nothing, so it needs no schema (invariant 11).
        writeService = new WriteService(store, new RepoSchema(""));
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void records_over_the_text_limits_and_outside_the_schema_are_imported_unchanged() {
        String longSummary = "s".repeat(301) + " İzmir";
        String longFactValue = "v".repeat(5_000);
        String longBody = "b".repeat(40_000);
        Node entity =
                new Node(
                        "big-entity",
                        List.of("Entity", "Server"),
                        Map.of(
                                "aliases",
                                List.of(),
                                "status",
                                "current",
                                "summary",
                                longSummary,
                                "updated_at",
                                AT));
        Node fact =
                new Node(
                        "01BIGFACT00000000000000001",
                        List.of("Fact"),
                        Map.of(
                                "env",
                                "default",
                                "key",
                                "note",
                                "source",
                                "agent",
                                "status",
                                "ok",
                                "value",
                                longFactValue,
                                "updated_at",
                                AT,
                                "valid_from",
                                AT,
                                "observed_at",
                                AT));
        Node lesson =
                new Node(
                        "ders-uzun",
                        List.of("Lesson"),
                        Map.of("title", "t", "body", longBody, "updated_at", AT));
        Edge hasFact =
                new Edge(
                        "01BIGEDGE00000000000000001",
                        "HAS_FACT",
                        "big-entity",
                        "01BIGFACT00000000000000001",
                        Map.of("updated_at", AT));
        // Not in schema/relations.yaml: kb check reports it, import must not drop it.
        Edge unknownType =
                new Edge(
                        "01BIGEDGE00000000000000002",
                        "LEGACY_LINK",
                        "ders-uzun",
                        "big-entity",
                        Map.of("updated_at", AT));
        Snapshot input = new Snapshot(List.of(entity, fact, lesson), List.of(hasFact, unknownType));

        writeService.importRecords(input);

        Snapshot exported = new SnapshotExporter(store).export();
        assertThat(exported.nodes()).containsExactlyInAnyOrderElementsOf(input.nodes());
        assertThat(exported.edges()).containsExactlyInAnyOrderElementsOf(input.edges());
        assertThat(propertyLength("big-entity", "summary")).isEqualTo(longSummary.length());
        assertThat(propertyLength("01BIGFACT00000000000000001", "value")).isEqualTo(5_000);
        assertThat(propertyLength("ders-uzun", "body")).isEqualTo(40_000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void an_edge_with_a_missing_endpoint_fails_naming_the_edge_and_leaves_an_empty_graph(
            boolean missingTarget) {
        Node first = new Node("node-a", List.of("Topic"), Map.of("updated_at", AT));
        Node second = new Node("node-b", List.of("Topic"), Map.of("updated_at", AT));
        Edge valid = new Edge("01VALIDEDGE000000000000001", "ABOUT", "node-a", "node-b", Map.of());
        Edge orphan =
                missingTarget
                        ? new Edge(ORPHAN_EDGE_ID, "ABOUT", "node-a", MISSING_NODE, Map.of())
                        : new Edge(ORPHAN_EDGE_ID, "ABOUT", MISSING_NODE, "node-b", Map.of());

        assertThatThrownBy(
                        () ->
                                writeService.importRecords(
                                        new Snapshot(
                                                List.of(first, second), List.of(valid, orphan))))
                .hasMessageContaining(ORPHAN_EDGE_ID)
                .hasMessageContaining(MISSING_NODE);

        assertThat(count("MATCH (n) RETURN count(n) AS v")).isZero();
        assertThat(count("MATCH ()-[r]->() RETURN count(r) AS v")).isZero();
    }

    @Test
    void a_failed_import_does_not_block_a_later_valid_import() {
        Node node = new Node("node-a", List.of("Topic"), Map.of("updated_at", AT));
        Edge orphan = new Edge(ORPHAN_EDGE_ID, "ABOUT", "node-a", MISSING_NODE, Map.of());
        assertThatThrownBy(
                () -> writeService.importRecords(new Snapshot(List.of(node), List.of(orphan))));

        writeService.importRecords(new Snapshot(List.of(node), List.of()));

        assertThat(count("MATCH (n:Topic {id: 'node-a'}) RETURN count(n) AS v")).isEqualTo(1);
    }

    private int propertyLength(String id, String property) {
        return ((String)
                        store.database()
                                .executeTransactionally(
                                        "MATCH (n {id: $id}) RETURN n[$property] AS v",
                                        Map.of("id", id, "property", property),
                                        result -> result.next().get("v")))
                .length();
    }

    private int count(String cypher) {
        return ((Number)
                        store.database()
                                .executeTransactionally(
                                        cypher, Map.of(), result -> result.next().get("v")))
                .intValue();
    }
}
