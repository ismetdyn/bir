package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.neo4j.graphdb.Result;

/**
 * The child JVM of {@link RestorePerformanceTest}: fills a graph with one generated snapshot, then
 * times {@code replaceAll} with another of the same size, so the restore deletes and creates.
 * Prints {@code restore_ms=<n> nodes=<n> edges=<n>}; any failure, an OutOfMemoryError included, is
 * a non-zero exit.
 */
final class RestoreBenchmark {

    static final int ENTITIES = 2_000;
    static final int FACTS_PER_ENTITY = 4;
    private static final String AT = "2026-10-01T09:00:00Z";

    private RestoreBenchmark() {}

    public static void main(String[] args) {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(Path.of(args[0]))) {
            WriteService writes = new WriteService(store, new RepoSchema(""));
            writes.importRecords(generated("old"));
            Snapshot snapshot = generated("new");
            long start = System.nanoTime();
            writes.replaceAll(snapshot);
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf(
                    Locale.ROOT,
                    "restore_ms=%d nodes=%d edges=%d%n",
                    millis,
                    count(store, "MATCH (n) RETURN count(n) AS n"),
                    count(store, "MATCH ()-[r]->() RETURN count(r) AS n"));
        }
    }

    /** {@link #ENTITIES} servers, each with {@link #FACTS_PER_ENTITY} facts: 10,000 nodes. */
    static Snapshot generated(String prefix) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int e = 0; e < ENTITIES; e++) {
            String entity = prefix + "-host-" + e;
            nodes.add(
                    new Node(
                            entity,
                            List.of("Entity", "Server"),
                            Map.of(
                                    "aliases",
                                    List.of(entity + ".ornek.example"),
                                    "status",
                                    "current",
                                    "summary",
                                    "kbtest server " + e,
                                    "updated_at",
                                    AT)));
            for (int f = 0; f < FACTS_PER_ENTITY; f++) {
                String fact = String.format(Locale.ROOT, "%s-fact-%06d-%d", prefix, e, f);
                nodes.add(
                        new Node(
                                fact,
                                List.of("Fact"),
                                Map.of(
                                        "env",
                                        "prod",
                                        "key",
                                        "ip",
                                        "source",
                                        "scan",
                                        "status",
                                        "ok",
                                        "updated_at",
                                        AT,
                                        "valid_from",
                                        AT,
                                        "value",
                                        "198.51.100." + f)));
                edges.add(
                        new Edge(
                                fact + "-edge",
                                "HAS_FACT",
                                entity,
                                fact,
                                Map.of("updated_at", AT)));
            }
        }
        return new Snapshot(nodes, edges);
    }

    private static long count(EmbeddedNeo4jStore store, String cypher) {
        return store.read(
                tx -> {
                    try (Result result = tx.execute(cypher)) {
                        return (Long) result.next().get("n");
                    }
                });
    }
}
