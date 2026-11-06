package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.untyped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotHistory;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb restore [--at <commit>]} (SPEC §10, ARCHITECTURE §8, ADR-0022 decision 38): {@code
 * GraphMaintenance.replaceAll(snapshot)} rebuilds the graph from the snapshot in one transaction
 * (invariant 1) and gives {@code RestoreResult(nodes, edges, keptSecrets, keptSignals,
 * droppedEdges)}. Local data stays: every Signal, every secret_value, a Secret the snapshot lacks;
 * an edge of a kept record whose other end the snapshot lacks is the only thing that goes. Rows are
 * never refused or cut (invariant 11). A restore that fails leaves the graph as it was.
 */
class RestoreTest {

    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final String SEED_SECRET = "ornek-app-01/prod/deploy-ssh-key";
    private static final String LB_SECRET = "ornek-lb/prod/panel-admin";
    private static final String NEW_SECRET = "ornek-app-01/prod/kbtest-local-token";
    private static final String VALUE = "kbtest-FAKE-restore-6b0e2d41";
    private static final String LB_VALUE = "kbtest-FAKE-restore-lb-93c7f5a8";
    private static final String NEW_VALUE = "kbtest-FAKE-restore-new-1a4d8e62";
    private static final String SIGNAL_ON_LB = "01KBTESTSIGNALONLB00000001";
    private static final String SIGNAL_ON_APP = "01KBTESTSIGNALONAPP0000001";
    private static final String SIGNALS =
            "MATCH (s:Signal) OPTIONAL MATCH (s)-[:ABOUT]->(t) RETURN s.id AS id,"
                    + " properties(s) AS props, collect(t.id) AS about ORDER BY id";
    private static final String HOLDER =
            "MATCH (e)-[:HAS_SECRET]->(:Secret {id: $id}) RETURN e.id AS id";
    private static final String ABOUT = "MATCH (:Signal {id: $id})-[:ABOUT]->(t) RETURN t.id AS id";
    private static final String SECRET_VALUE = SecretWrite.SECRET_VALUE;

    @TempDir Path dir;
    @TempDir Path out;

    private WriteHarness graph;

    @BeforeEach
    void seedGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.writes.maintenance().importRecords(SnapshotRepos.seed());
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    /** What happened after the snapshot is gone: a new entity and a closed seed fact. */
    @Test
    void restoring_the_seed_gives_back_the_seed_byte_for_byte() throws IOException {
        graph.write(request(Source.USER, "zz-after-snapshot", T1, fact("os", "rhel 9", PROD)));
        graph.write(
                untyped(Source.USER, "ornek-app-01", T1, fact("os", "Rocky Linux 10", "default")));
        assertThat(graph.openFacts("ornek-app-01", "os", "default"))
                .as("precondition: the seed's os was closed")
                .extracting(f -> f.get("value"))
                .containsExactly("Rocky Linux 10");

        RestoreResult result = graph.writes.maintenance().replaceAll(SnapshotRepos.seed());

        SnapshotFormat.write(out, new SnapshotExporter(graph.store).export());
        for (String file : List.of(SnapshotFormat.NODES_FILE, SnapshotFormat.EDGES_FILE)) {
            assertThat(Files.readString(out.resolve(file), StandardCharsets.UTF_8))
                    .as(file)
                    .isEqualTo(
                            Files.readString(
                                    SnapshotRepos.SEED_SNAPSHOT.resolve(file),
                                    StandardCharsets.UTF_8));
            assertThat(out.resolve(file))
                    .hasSameBinaryContentAs(SnapshotRepos.SEED_SNAPSHOT.resolve(file));
        }
        assertThat(result.nodes()).isEqualTo(SnapshotRepos.seed().nodes().size());
        assertThat(result.edges()).isEqualTo(SnapshotRepos.seed().edges().size());
        assertThat(result.keptSecrets()).isZero();
        assertThat(result.keptSignals()).isZero();
        assertThat(result.droppedEdges()).isZero();
    }

    /**
     * SPEC §10: secret values and signals are local, so the snapshot never has them and a restore
     * must not lose them. A Secret set here since the snapshot stays under its entity.
     */
    @Test
    void restoring_the_same_snapshot_keeps_secret_values_and_signals() {
        graph.writes.putSecret(SEED_SECRET, VALUE);
        graph.writes.putSecret(NEW_SECRET, NEW_VALUE);
        graph.writes.raiseSignal("qa-capture", "kbtest question", "kbtest answer", T1);
        // An agent disputes the user's hostname: a conflict signal ABOUT ornek-app-01.
        graph.write(
                untyped(
                        Source.AGENT,
                        "ornek-app-01",
                        T1,
                        fact("hostname", "kbtest-other.ornek.example", PROD)));
        List<Map<String, Object>> signals = graph.rows(SIGNALS, Map.of());
        assertThat(signals).as("precondition").hasSize(2);

        RestoreResult result = graph.writes.maintenance().replaceAll(SnapshotRepos.seed());

        assertThat(property(SEED_SECRET, SECRET_VALUE)).isEqualTo(VALUE);
        assertThat(property(NEW_SECRET, SECRET_VALUE)).isEqualTo(NEW_VALUE);
        assertThat(ids(HOLDER, NEW_SECRET)).containsExactly("ornek-app-01");
        assertThat(graph.rows(SIGNALS, Map.of())).isEqualTo(signals);
        assertThat(result.keptSecrets()).isEqualTo(1);
        assertThat(result.keptSignals()).isEqualTo(2);
        assertThat(result.droppedEdges()).isZero();
    }

    /**
     * ADR-0022 decision 38 with {@code --at}: the older commit has no ornek-lb and so neither its
     * Secret nor the target of a signal. The Secret stays with its value, the signal stays; only
     * the edges to the missing entity go, and the result counts them.
     */
    @Test
    void restoring_an_older_commit_keeps_local_records_and_drops_only_dangling_edges()
            throws IOException {
        Snapshot older = SnapshotRepos.without(SnapshotRepos.seed(), Set.of("ornek-lb", LB_SECRET));
        Path repo = SnapshotRepos.repo(dir.resolve("kb"), older);
        SnapshotRepos.commit(repo, SnapshotRepos.seed(), "seed");
        graph.writes.putSecret(LB_SECRET, LB_VALUE);
        signalAbout(SIGNAL_ON_LB, "ornek-lb");
        signalAbout(SIGNAL_ON_APP, "ornek-app-01");

        RestoreResult result =
                graph.writes.maintenance().replaceAll(SnapshotHistory.read(repo, "HEAD~1"));

        assertThat(property(LB_SECRET, SECRET_VALUE)).isEqualTo(LB_VALUE);
        assertThat(ids(HOLDER, LB_SECRET)).isEmpty();
        assertThat(ids("MATCH (e:Entity {id: $id}) RETURN e.id AS id", "ornek-lb")).isEmpty();
        assertThat(ids(ABOUT, SIGNAL_ON_LB)).as("edge to the missing entity").isEmpty();
        assertThat(ids(ABOUT, SIGNAL_ON_APP)).containsExactly("ornek-app-01");
        assertThat(result.nodes()).isEqualTo(older.nodes().size());
        assertThat(result.edges()).isEqualTo(older.edges().size());
        assertThat(result.keptSecrets()).isEqualTo(1);
        assertThat(result.keptSignals()).isEqualTo(2);
        assertThat(result.droppedEdges()).as("ABOUT ornek-lb").isEqualTo(1);
        Snapshot exported = new SnapshotExporter(graph.store).export();
        assertThat(exported.nodes().stream().map(Node::id))
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(older.nodes().stream().map(Node::id), Stream.of(LB_SECRET))
                                .toList());
        assertThat(exported.edges()).hasSize(older.edges().size());
    }

    /** Invariant 11: limits apply to new writes only; a restore takes a row as it is. */
    @Test
    void a_record_over_the_text_limits_is_restored_unchanged() {
        String longSummary = "s".repeat(301) + " İzmir";
        String longValue = "v".repeat(5_000);
        List<Node> nodes = new ArrayList<>(SnapshotRepos.seed().nodes());
        nodes.add(
                new Node(
                        "kbtest-long",
                        List.of("Entity", "Server"),
                        Map.of(
                                "aliases",
                                List.of(),
                                "status",
                                "current",
                                "summary",
                                longSummary,
                                "updated_at",
                                "2026-10-01T09:00:00Z")));
        nodes.add(
                new Node(
                        "01KBTESTLONGFACT0000000001",
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
                                "updated_at",
                                "2026-10-01T09:00:00Z",
                                "valid_from",
                                "2026-10-01T09:00:00Z",
                                "value",
                                longValue)));
        List<Edge> edges = new ArrayList<>(SnapshotRepos.seed().edges());
        edges.add(
                new Edge(
                        "01KBTESTLONGEDGE0000000001",
                        "HAS_FACT",
                        "kbtest-long",
                        "01KBTESTLONGFACT0000000001",
                        Map.of("updated_at", "2026-10-01T09:00:00Z")));

        graph.writes.maintenance().replaceAll(new Snapshot(nodes, edges));

        assertThat(property("kbtest-long", "summary")).isEqualTo(longSummary);
        assertThat(property("01KBTESTLONGFACT0000000001", "value")).isEqualTo(longValue);
    }

    /**
     * A snapshot that cannot be a graph (an edge to a node it lacks) fails inside the restore; the
     * one transaction rolls back and the graph, local data included, is as it was.
     */
    @Test
    void a_failing_restore_leaves_the_graph_unchanged() {
        graph.writes.putSecret(SEED_SECRET, VALUE);
        signalAbout(SIGNAL_ON_APP, "ornek-app-01");
        graph.write(request(Source.USER, "zz-after-snapshot", T1, fact("os", "rhel 9", PROD)));
        Map<String, Object> before = graph.state();
        List<Edge> edges = new ArrayList<>(SnapshotRepos.seed().edges());
        edges.add(
                new Edge(
                        "01KBTESTORPHANEDGE00000001",
                        "DEPENDS_ON",
                        "ornek-app-01",
                        "kbtest-ghost",
                        Map.of()));
        Snapshot broken = new Snapshot(SnapshotRepos.seed().nodes(), edges);

        assertThatThrownBy(() -> graph.writes.maintenance().replaceAll(broken))
                .isInstanceOf(RuntimeException.class);

        assertThat(graph.state()).isEqualTo(before);
        assertThat(property(SEED_SECRET, SECRET_VALUE)).isEqualTo(VALUE);
    }

    private void signalAbout(String id, String target) {
        graph.store
                .database()
                .executeTransactionally(
                        """
                        MATCH (t:Entity {id: $target})
                        CREATE (s:Signal {id: $id, kind: 'conflict', key: $id, payload: 'kbtest',
                                          status: 'open', count: 1, at: '2026-11-01T09:00:00Z',
                                          last_at: '2026-11-01T09:00:00Z'})
                        CREATE (s)-[:ABOUT {id: $id + '-about'}]->(t)
                        """,
                        Map.of("id", id, "target", target));
    }

    private Object property(String id, String key) {
        List<Map<String, Object>> rows =
                graph.rows("MATCH (n {id: $id}) RETURN n[$key] AS v", Map.of("id", id, "key", key));
        assertThat(rows).as("one record %s", id).hasSize(1);
        return rows.getFirst().get("v");
    }

    /** The ids a one-parameter query ({@code $id}) returns as {@code id}. */
    private List<Object> ids(String cypher, String id) {
        return graph.rows(cypher, Map.of("id", id)).stream().map(row -> row.get("id")).toList();
    }
}
