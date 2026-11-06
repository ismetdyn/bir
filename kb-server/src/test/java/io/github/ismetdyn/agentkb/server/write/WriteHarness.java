package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotMerge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.neo4j.graphdb.Result;

/**
 * A fresh graph with the seed schema for the write-path tests. Reads go through Cypher, so they see
 * Signal nodes too, which the snapshot export leaves out (invariant 8).
 */
final class WriteHarness implements AutoCloseable {

    /** ARCHITECTURE §4: facts, edges, proposals and signals have ULID ids. */
    static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    static final String UPDATED_AT = "updated_at";

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");
    private static final String NODES =
            "MATCH (n) RETURN n.id AS id, labels(n) AS labels, properties(n) AS props";
    private static final String EDGES =
            "MATCH (a)-[r]->(b) RETURN r.id AS id, type(r) AS type, a.id AS from, b.id AS to,"
                    + " properties(r) AS props";

    final EmbeddedNeo4jStore store;
    final WriteService writes;

    private WriteHarness(EmbeddedNeo4jStore store, WriteService writes) {
        this.store = store;
        this.writes = writes;
    }

    static WriteHarness open(Path dir) throws IOException {
        Path repo = dir.resolve("repo");
        Path schema = Files.createDirectories(repo.resolve("schema"));
        for (String file : SCHEMA_FILES) {
            Files.copy(SEED_SCHEMA.resolve(file), schema.resolve(file));
        }
        EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dir.resolve("data"));
        WriteService writes = new WriteService(store, new RepoSchema(repo.toString()));
        writes.ensureSchema();
        return new WriteHarness(store, writes);
    }

    WriteResult write(WriteRequest request) {
        return writes.write(request);
    }

    /** The properties (with id) of every fact of {@code entity}, open and closed. */
    List<Map<String, Object>> facts(String entity) {
        return rows(
                        "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact) RETURN properties(f) AS f",
                        Map.of("entity", entity))
                .stream()
                .map(row -> GraphRows.props(row.get("f")))
                .toList();
    }

    List<Map<String, Object>> openFacts(String entity, String key, String env) {
        return facts(entity).stream()
                .filter(f -> key.equals(f.get("key")) && env.equals(f.get("env")))
                .filter(f -> !f.containsKey("valid_to"))
                .toList();
    }

    /** Rows of a read query; arrays become lists so that rows compare by value. */
    List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(
                tx -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try (Result result = tx.execute(cypher, params)) {
                        result.forEachRemaining(row -> rows.add(GraphRows.props(row)));
                    }
                    return rows;
                });
    }

    /** Every node and edge, Signal ones included, by id; {@code ignored} properties left out. */
    Map<String, Object> state(String... ignored) {
        Set<String> skip = Set.of(ignored);
        Map<String, Object> state = new TreeMap<>();
        for (Map<String, Object> node : rows(NODES, Map.of())) {
            List<Object> labels = new ArrayList<>((List<?>) node.get("labels"));
            labels.sort(null);
            state.put(
                    "node " + node.get("id"),
                    List.of(labels, GraphRows.without(node.get("props"), skip)));
        }
        for (Map<String, Object> edge : rows(EDGES, Map.of())) {
            state.put(
                    "edge " + edge.get("id"),
                    List.of(
                            edge.get("type"),
                            edge.get("from"),
                            edge.get("to"),
                            GraphRows.without(edge.get("props"), skip)));
        }
        return state;
    }

    /**
     * {@link #state} without the RevertedBatch nodes a revert leaves (ADR-0022 decision 42), for
     * comparing the graph before a batch with the graph after its revert.
     */
    Map<String, Object> stateWithoutRevertMarkers(String... ignored) {
        Map<String, Object> state = state(ignored);
        state.values()
                .removeIf(
                        record ->
                                ((List<?>) record)
                                        .getFirst()
                                        .equals(List.of(SnapshotMerge.REVERTED_BATCH)));
        return state;
    }

    /** The ids of the RevertedBatch nodes that carry {@code batch} as their id. */
    List<Object> revertMarkers(String batch) {
        return rows(
                        "MATCH (t:" + SnapshotMerge.REVERTED_BATCH + " {id: $b}) RETURN t.id AS id",
                        Map.of("b", batch))
                .stream()
                .map(row -> row.get("id"))
                .toList();
    }

    @Override
    public void close() {
        store.close();
    }
}
