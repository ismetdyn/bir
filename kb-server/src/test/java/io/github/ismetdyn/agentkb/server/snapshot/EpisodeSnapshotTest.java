package io.github.ismetdyn.agentkb.server.snapshot;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0023 decision 4, SPEC §10, invariant 8: Episode nodes and the {@code episode} property go
 * into the deterministic snapshot (provenance travels with sync; unlike Signal it is not local),
 * and a restore of that snapshot gives the same graph back.
 */
class EpisodeSnapshotTest extends RunningServerTestBase {

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private WriteService writes;

    @TempDir Path cwd;

    @Test
    void episodes_and_their_stamps_are_exported_and_survive_a_restore() throws Exception {
        EpisodeProbe probe = new EpisodeProbe(port, store);
        Session session = probe.start("codex", newSessionId(), cwd);
        String reply =
                McpClient.open(port)
                        .text(
                                "kb_remember",
                                Map.of(
                                        "entity",
                                        "zz-g12b-snapshot",
                                        "type",
                                        "server",
                                        "facts",
                                        List.of(Map.of("key", "os", "value", "rhel 9")),
                                        "source",
                                        Map.of("kind", "code", "episode", session.episode())));
        String batch = EpisodeProbe.batch(reply);

        Snapshot before = new SnapshotExporter(store).export();

        assertThat(before.nodes())
                .as("the Episode row")
                .anySatisfy(
                        node -> {
                            assertThat(node.id()).isEqualTo(session.episode());
                            assertThat(node.labels()).containsExactly("Episode");
                            assertThat(node.props())
                                    .containsEntry("kind", "session")
                                    .containsEntry("client", "codex")
                                    .containsEntry("session_id", session.sessionId());
                        });
        assertThat(before.nodes())
                .filteredOn(node -> batch.equals(node.props().get("batch")))
                .isNotEmpty()
                .allSatisfy(
                        node ->
                                assertThat(node.props())
                                        .containsEntry("episode", session.episode()));
        assertThat(before.edges())
                .filteredOn(edge -> batch.equals(edge.props().get("batch")))
                .isNotEmpty()
                .allSatisfy(
                        edge ->
                                assertThat(edge.props())
                                        .containsEntry("episode", session.episode()));

        writes.replaceAll(before);

        assertThat(new SnapshotExporter(store).export()).isEqualTo(before);
        assertThat(probe.episode(session.episode())).isPresent();
    }
}
