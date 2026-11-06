package io.github.ismetdyn.agentkb.server.snapshot;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.EDGES;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.NODES;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.SEED_SNAPSHOT;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.assertSameFiles;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;

/**
 * G4: seed/snapshot goes into the graph through GraphMaintenance.importRecords and comes back out
 * through SnapshotExporter unchanged (ADR-0022 decision 9, invariants 8 and 11).
 */
class SeedRoundTripTest {

    private static final String SECRET_ID = "ornek-proje/prod/spring.datasource.password";
    private static final String FAKE_SECRET = "SENTINEL-secret-value-7f3a9c";
    private static final String SIGNAL_PAYLOAD = "SENTINEL-signal-payload-41d2";

    @TempDir Path dataDir;
    @TempDir Path out;

    private EmbeddedNeo4jStore store;

    @BeforeEach
    void importSeed() throws IOException {
        store = new EmbeddedNeo4jStore(dataDir);
        new WriteService(store, new RepoSchema(""))
                .maintenance()
                .importRecords(SnapshotFormat.read(SEED_SNAPSHOT));
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void exporting_the_imported_seed_gives_byte_identical_files() throws IOException {
        SnapshotFormat.write(out, new SnapshotExporter(store).export());

        assertSameFiles(SEED_SNAPSHOT, out);
    }

    @Test
    void import_keeps_updated_at_from_the_snapshot_instead_of_stamping_now() {
        // Fact 192.0.2.20: observed 2026-01-15, closed and updated 2026-05-10.
        Object updatedAt =
                single(
                        "MATCH (f:Fact {id: '01KF0E44M0JB0CE671YFMPJS8A'}) RETURN f.updated_at AS v",
                        Map.of());
        Object edgeUpdatedAt =
                single(
                        "MATCH ()-[r:DEPLOYED_ON {id: '01KF0E44M07NA59J9S50ZD2R86'}]->() RETURN r.updated_at AS v",
                        Map.of());

        // A time is text in the one form of Times: export has no JSON form for a Neo4j temporal,
        // and only a fixed length keeps text order equal to time order.
        String expected = Times.format(Instant.parse("2026-05-10T09:00:00Z"));
        assertThat(updatedAt).isEqualTo(expected);
        assertThat(edgeUpdatedAt).isEqualTo(expected);
    }

    @Test
    void graph_holds_exactly_the_seed_rows() throws IOException {
        assertThat(count("MATCH (n) RETURN count(n) AS v"))
                .isEqualTo(Files.readAllLines(SEED_SNAPSHOT.resolve(NODES)).size());
        assertThat(count("MATCH ()-[r]->() RETURN count(r) AS v"))
                .isEqualTo(Files.readAllLines(SEED_SNAPSHOT.resolve(EDGES)).size());
    }

    /** Invariant 8: signals, the edges touching them and secret_value stay local. */
    @Test
    void signals_their_edges_and_secret_values_never_reach_the_export() throws IOException {
        GraphDatabaseService db = store.database();
        db.executeTransactionally(
                "MATCH (s:Secret {id: $secret}) SET s.secret_value = $value",
                Map.of("secret", SECRET_ID, "value", FAKE_SECRET));
        db.executeTransactionally(
                """
                MATCH (e:Entity {id: 'ornek-app-01'}), (l:Lesson {id: 'ders-ornek-tls-yaniltici-test'})
                CREATE (s:Signal {id: '01SIGNAL0000000000000000AA', kind: 'conflict', key: 'ip',
                                  payload: $payload, status: 'open', count: 2,
                                  at: '2026-11-01T09:00:00Z', last_at: '2026-11-01T09:00:00Z',
                                  updated_at: '2026-11-01T09:00:00Z'})
                CREATE (s)-[:ABOUT {id: '01SIGNALEDGE0000000000OUT1', updated_at: '2026-11-01T09:00:00Z'}]->(e)
                CREATE (l)-[:ABOUT {id: '01SIGNALEDGE00000000000IN1', updated_at: '2026-11-01T09:00:00Z'}]->(s)
                """,
                Map.of("payload", SIGNAL_PAYLOAD));
        assertThat(count("MATCH (s:Signal) RETURN count(s) AS v")).isEqualTo(1);
        assertThat(
                        single(
                                "MATCH (s:Secret {id: $id}) RETURN s.secret_value AS v",
                                Map.of("id", SECRET_ID)))
                .isEqualTo(FAKE_SECRET);

        Snapshot exported = new SnapshotExporter(store).export();
        SnapshotFormat.write(out, exported);

        assertSameFiles(SEED_SNAPSHOT, out);
        String files =
                Files.readString(out.resolve(NODES), StandardCharsets.UTF_8)
                        + Files.readString(out.resolve(EDGES), StandardCharsets.UTF_8);
        assertThat(files)
                .doesNotContain(FAKE_SECRET, SIGNAL_PAYLOAD, "secret_value", "Signal", "01SIGNAL");
        assertThat(exported.nodes())
                .noneMatch(node -> node.labels().contains("Signal"))
                .noneMatch(node -> node.props().containsKey("secret_value"));
    }

    @Test
    void export_row_count_equals_graph_count_without_signals() {
        store.database()
                .executeTransactionally(
                        """
                        MATCH (e:Entity {id: 'ornek-app-01'})
                        CREATE (s:Signal {id: '01SIGNAL0000000000000000BB', kind: 'qa-capture', key: 'k',
                                          status: 'open', updated_at: '2026-11-01T09:00:00Z'})
                        CREATE (s)-[:ABOUT {id: '01SIGNALEDGE0000000000OUT2', updated_at: '2026-11-01T09:00:00Z'}]->(e)
                        """);

        Snapshot exported = new SnapshotExporter(store).export();

        assertThat(exported.nodes())
                .hasSize(count("MATCH (n) WHERE NOT n:Signal RETURN count(n) AS v"));
        assertThat(exported.edges())
                .hasSize(
                        count(
                                "MATCH (a)-[r]->(b) WHERE NOT a:Signal AND NOT b:Signal RETURN count(r) AS v"));
    }

    private Object single(String cypher, Map<String, Object> params) {
        return store.database()
                .executeTransactionally(
                        cypher, params, result -> result.hasNext() ? result.next().get("v") : null);
    }

    private int count(String cypher) {
        return ((Number) single(cypher, Map.of())).intValue();
    }
}
