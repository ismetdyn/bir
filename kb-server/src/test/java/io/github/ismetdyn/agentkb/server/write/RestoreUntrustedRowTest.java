package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariant 2 on {@code kb restore}: a snapshot never carries secret_value (SnapshotExporter strips
 * it), so a row that does is untrusted input, e.g. a commit pulled from a shared remote. The
 * planted value must not become the one {@code kb run} injects, and a {@code props.id} must not
 * move a record to another id. The row itself stays (invariant 11); the result counts the value.
 */
class RestoreUntrustedRowTest {

    private static final String SEED_SECRET = "ornek-app-01/prod/deploy-ssh-key";
    private static final String OTHER_ID = "ornek-app-01/prod/kbtest-other";
    private static final String PLANTED = "kbtest-FAKE-planted-0c7e5b19";
    private static final String HOLDER =
            "MATCH (e)-[:HAS_SECRET]->(:Secret {id: $id}) RETURN e.id AS id";

    @TempDir Path dir;

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

    @Test
    void a_secret_value_or_id_in_a_snapshot_row_is_not_restored() {
        List<Node> nodes =
                SnapshotRepos.seed().nodes().stream()
                        .map(node -> node.id().equals(SEED_SECRET) ? planted(node) : node)
                        .toList();

        RestoreResult result =
                graph.writes
                        .maintenance()
                        .replaceAll(new Snapshot(nodes, SnapshotRepos.seed().edges()));

        assertThat(graph.state().toString()).doesNotContain(PLANTED);
        assertThat(holders(SEED_SECRET)).containsExactly("ornek-app-01");
        assertThat(holders(OTHER_ID)).isEmpty();
        assertThat(result.droppedSecretValues()).isEqualTo(1);
    }

    private static Node planted(Node node) {
        Map<String, Object> props = new HashMap<>(node.props());
        props.put(SecretWrite.SECRET_VALUE, PLANTED);
        props.put("id", OTHER_ID);
        return new Node(node.id(), node.labels(), props);
    }

    private List<Object> holders(String id) {
        return graph.rows(HOLDER, Map.of("id", id)).stream().map(row -> row.get("id")).toList();
    }
}
