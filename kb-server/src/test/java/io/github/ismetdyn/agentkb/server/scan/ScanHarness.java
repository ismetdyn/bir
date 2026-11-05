package io.github.ismetdyn.agentkb.server.scan;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.neo4j.graphdb.Result;

/**
 * A fresh graph with the seed schema and a scanner whose only allowed root is {@link #root}, with
 * internal domain ornek.example as in fixtures/scanner/expected.yaml. A fresh graph keeps the seed
 * graph of the running-server tests untouched; the scanner writes through WriteService like in the
 * server (ADR-0022 decision 31).
 */
final class ScanHarness implements AutoCloseable {

    static final String INTERNAL_DOMAIN = "ornek.example";

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");
    private static final String NODES =
            "MATCH (n) RETURN n.id AS id, labels(n) AS labels, properties(n) AS props";
    private static final String EDGES =
            "MATCH (a)-[r]->(b) RETURN r.id AS id, type(r) AS type, a.id AS from, b.id AS to,"
                    + " properties(r) AS props";

    final Path root;
    final EmbeddedNeo4jStore store;
    final ScannerService scanner;
    // For records a test needs in the graph before a scan (the one writer, invariant 1).
    final WriteService writes;

    private ScanHarness(
            Path root, EmbeddedNeo4jStore store, ScannerService scanner, WriteService writes) {
        this.root = root;
        this.store = store;
        this.scanner = scanner;
        this.writes = writes;
    }

    static ScanHarness open(Path dir) throws IOException {
        Path repo = dir.resolve("repo");
        Path schema = Files.createDirectories(repo.resolve("schema"));
        for (String file : SCHEMA_FILES) {
            Files.copy(SEED_SCHEMA.resolve(file), schema.resolve(file));
        }
        Path root = Files.createDirectories(dir.resolve("projects"));
        EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dir.resolve("data"));
        WriteService writes = new WriteService(store, new RepoSchema(repo.toString()));
        writes.ensureSchema();
        ScannerService scanner =
                new ScannerService(
                        writes, new ScanProperties(List.of(root), List.of(INTERNAL_DOMAIN)));
        return new ScanHarness(root, store, scanner, writes);
    }

    /** Rows of a read query; arrays become lists so that rows compare by value. */
    List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(
                tx -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try (Result result = tx.execute(cypher, params)) {
                        result.forEachRemaining(row -> rows.add(plain(row)));
                    }
                    return rows;
                });
    }

    List<Map<String, Object>> rows(String cypher) {
        return rows(cypher, Map.of());
    }

    /** Every node and edge by id, with {@code ignored} properties left out. */
    Map<String, Object> state(String... ignored) {
        Set<String> skip = Set.of(ignored);
        Map<String, Object> state = new TreeMap<>();
        for (Map<String, Object> node : rows(NODES)) {
            List<Object> labels = new ArrayList<>((List<?>) node.get("labels"));
            labels.sort(null);
            state.put("node " + node.get("id"), List.of(labels, without(node.get("props"), skip)));
        }
        for (Map<String, Object> edge : rows(EDGES)) {
            state.put(
                    "edge " + edge.get("id"),
                    List.of(
                            edge.get("type"),
                            edge.get("from"),
                            edge.get("to"),
                            without(edge.get("props"), skip)));
        }
        return state;
    }

    private static Map<String, Object> without(Object props, Set<String> skip) {
        Map<String, Object> kept = new TreeMap<>(plain((Map<?, ?>) props));
        kept.keySet().removeAll(skip);
        return kept;
    }

    private static Map<String, Object> plain(Map<?, ?> row) {
        Map<String, Object> plain = new LinkedHashMap<>();
        row.forEach((key, value) -> plain.put((String) key, plain(value)));
        return plain;
    }

    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            return plain(map);
        }
        if (value != null && value.getClass().isArray()) {
            // Neo4j array properties (String[], long[]) compare by value only as lists.
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                list.add(Array.get(value, i));
            }
            return list;
        }
        return value;
    }

    @Override
    public void close() {
        store.close();
    }
}
