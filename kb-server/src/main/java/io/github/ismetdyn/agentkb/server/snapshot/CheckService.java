package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.core.BlobPaths;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.snapshot.CheckReport.Problem;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code kb check} (ARCHITECTURE §13, ADR-0022 decisions 6 and 38): reads the graph, the snapshot
 * files in the working tree of {@code <repo>/snapshot} (only there can an edge point to a missing
 * node), hashes each Document's blob (F3) and runs {@code git fsck}. Nothing is changed. Without a
 * repo only the graph is checked. Details name ids, keys and types, never a value (invariants 2 and
 * 10).
 */
@Component
public class CheckService {

    private static final String UNKNOWN_RELATIONS =
            """
            MATCH (a)-[r]->(b) WHERE NOT type(r) IN $allowed
            RETURN r.id AS id, type(r) AS type, a.id AS from, b.id AS to ORDER BY id
            """;
    private static final String OPEN_CONFLICTS =
            """
            MATCH (f:Fact {status: 'conflict'}) WHERE f.valid_to IS NULL
            OPTIONAL MATCH (e)-[:HAS_FACT]->(f)
            RETURN f.id AS id, e.id AS entity, f.key AS key, f.env AS env ORDER BY id
            """;
    private static final String DUPLICATE_NODE_IDS =
            """
            MATCH (n) WHERE n.id IS NOT NULL WITH n.id AS id, count(n) AS n WHERE n > 1
            RETURN id, n ORDER BY id
            """;
    private static final String DUPLICATE_EDGE_IDS =
            """
            MATCH ()-[r]->() WHERE r.id IS NOT NULL WITH r.id AS id, count(r) AS n WHERE n > 1
            RETURN id, n ORDER BY id
            """;
    private static final String UNHELD_SECRETS =
            "MATCH (s:Secret) WHERE NOT ()-[:HAS_SECRET]->(s) RETURN s.id AS id ORDER BY id";
    private static final String UNHELD_FACTS =
            "MATCH (f:Fact) WHERE NOT ()-[:HAS_FACT]->(f) RETURN f.id AS id ORDER BY id";
    private static final String CONFLICTS_ABOUT_NOTHING =
            """
            MATCH (s:Signal {kind: 'conflict'}) WHERE NOT (s)-[:ABOUT]->()
            RETURN s.id AS id, s.key AS key ORDER BY id
            """;
    private static final String DOCUMENTS =
            "MATCH (d:Document) RETURN d.id AS id, d.sha256 AS sha256 ORDER BY id";
    private static final String MISSING_BLOB = "missing-blob";
    private static final String BLOB_HASH_MISMATCH = "blob-hash-mismatch";
    private static final String SNAPSHOT_DIRECTORY = "snapshot";
    private static final int FSCK_LINES = 5;

    private final EmbeddedNeo4jStore store;
    private final String repo;

    public CheckService(EmbeddedNeo4jStore store, @Value("${agentkb.repo:}") String repo) {
        this.store = store;
        this.repo = repo;
    }

    public CheckReport check() {
        List<Problem> problems = new ArrayList<>();
        if (repo.isBlank()) {
            problems.addAll(store.read(tx -> graph(tx, List.of())));
            return new CheckReport(problems);
        }
        Path root = Path.of(repo);
        List<String> allowed = new ArrayList<>(Schema.FIXED_EDGE_TYPES);
        try {
            new RepoSchema(repo).load().relations().forEach(type -> allowed.add(type.type()));
        } catch (IllegalStateException e) {
            problems.add(
                    new Problem("unreadable", root.resolve("schema").toString(), e.getMessage()));
        }
        problems.addAll(orphanEdges(root.resolve(SNAPSHOT_DIRECTORY)));
        problems.addAll(store.read(tx -> graph(tx, allowed)));
        problems.addAll(blobs(root));
        problems.addAll(fsck(root));
        return new CheckReport(problems);
    }

    /** {@code allowed} empty: the vocabulary is unknown, so relation types are not checked. */
    private static List<Problem> graph(Transaction tx, List<String> allowed) {
        List<Problem> problems = new ArrayList<>();
        if (!allowed.isEmpty()) {
            problems.addAll(
                    rows(
                            tx,
                            UNKNOWN_RELATIONS,
                            Map.of("allowed", allowed),
                            "unknown-relation",
                            row ->
                                    "type "
                                            + row.get("type")
                                            + " from "
                                            + row.get("from")
                                            + " to "
                                            + row.get("to")
                                            + " is not in schema/relations.yaml"));
        }
        problems.addAll(
                rows(
                        tx,
                        OPEN_CONFLICTS,
                        Map.of(),
                        "open-conflict",
                        row ->
                                row.get("entity")
                                        + " "
                                        + row.get("key")
                                        + " @"
                                        + row.get("env")
                                        + ": a value waits as a conflict"));
        problems.addAll(
                Stream.of(DUPLICATE_NODE_IDS, DUPLICATE_EDGE_IDS)
                        .flatMap(
                                cypher ->
                                        rows(
                                                tx,
                                                cypher,
                                                Map.of(),
                                                "duplicate-id",
                                                row -> row.get("n") + " records carry this id")
                                                .stream())
                        .toList());
        problems.addAll(
                rows(
                        tx,
                        UNHELD_SECRETS,
                        Map.of(),
                        "local-record",
                        row -> "a Secret no entity holds; a restore kept it with its value"));
        problems.addAll(
                rows(
                        tx,
                        UNHELD_FACTS,
                        Map.of(),
                        "orphan-fact",
                        row ->
                                "a Fact no entity holds; a batch reverted on another machine"
                                        + " took its entity"));
        problems.addAll(
                rows(
                        tx,
                        CONFLICTS_ABOUT_NOTHING,
                        Map.of(),
                        "local-record",
                        row ->
                                "a conflict signal about "
                                        + row.get("key")
                                        + ", which is not in the graph"));
        return problems;
    }

    private static List<Problem> rows(
            Transaction tx,
            String cypher,
            Map<String, Object> params,
            String kind,
            Function<Map<String, Object>, String> detail) {
        try (Result result = tx.execute(cypher, params)) {
            return result.stream()
                    .map(row -> new Problem(kind, String.valueOf(row.get("id")), detail.apply(row)))
                    .toList();
        }
    }

    /** Edge rows of the working tree whose from or to is no node row. */
    private static List<Problem> orphanEdges(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Snapshot snapshot;
        try {
            snapshot = SnapshotFormat.read(dir);
        } catch (IOException e) {
            return List.of(new Problem("unreadable", dir.toString(), e.getMessage()));
        }
        Set<String> nodes = snapshot.nodes().stream().map(Node::id).collect(Collectors.toSet());
        List<Problem> problems = new ArrayList<>();
        for (Edge edge : snapshot.edges()) {
            Stream.of(edge.from(), edge.to())
                    .filter(end -> !nodes.contains(end))
                    .findFirst()
                    .ifPresent(
                            missing ->
                                    problems.add(
                                            new Problem(
                                                    "orphan-edge",
                                                    edge.id(),
                                                    edge.type()
                                                            + " points to missing node "
                                                            + missing)));
        }
        return problems;
    }

    /**
     * F3 (docs/CLIENTS.md §2): each Document's blob, hashed outside the read transaction. A problem
     * names the document and the kind, never a byte of the content (invariants 2 and 10).
     */
    private List<Problem> blobs(Path root) {
        List<Map<String, Object>> documents =
                store.read(
                        tx -> {
                            try (Result result = tx.execute(DOCUMENTS)) {
                                return result.stream().toList();
                            }
                        });
        List<Problem> problems = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            String id = String.valueOf(document.get("id"));
            if (document.get("sha256") instanceof String sha && BlobPaths.of(sha).isPresent()) {
                blobProblem(root, id, sha).ifPresent(problems::add);
            } else {
                problems.add(new Problem(MISSING_BLOB, id, "no valid sha256 to find its blob by"));
            }
        }
        return problems;
    }

    private static Optional<Problem> blobProblem(Path root, String id, String sha) {
        BlobFiles.State state;
        try {
            state = BlobFiles.state(root, sha);
        } catch (IOException e) {
            return Optional.of(new Problem("unreadable", id, "its blob could not be read"));
        }
        return switch (state) {
            case INTACT -> Optional.empty();
            case MISSING -> Optional.of(new Problem(MISSING_BLOB, id, "its blob is not in blobs/"));
            case NOT_A_FILE ->
                    Optional.of(
                            new Problem(
                                    BLOB_HASH_MISMATCH,
                                    id,
                                    "a link or directory stands in place of its blob"));
            case MISMATCH ->
                    Optional.of(
                            new Problem(
                                    BLOB_HASH_MISMATCH,
                                    id,
                                    "its blob's bytes do not hash to its sha256"));
        };
    }

    private static List<Problem> fsck(Path root) {
        String detail;
        try {
            SystemGit.Result fsck = SystemGit.run(root, true, "fsck", "--no-progress");
            if (fsck.ok()) {
                return List.of();
            }
            detail =
                    fsck.output()
                            .lines()
                            .filter(line -> !line.isBlank())
                            .limit(FSCK_LINES)
                            .collect(Collectors.joining("; "));
        } catch (IOException e) {
            detail = "git could not be run: " + e.getMessage();
        }
        return List.of(new Problem("git-fsck", root.toString(), detail));
    }
}
