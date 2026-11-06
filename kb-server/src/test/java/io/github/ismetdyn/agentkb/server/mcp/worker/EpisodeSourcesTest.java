package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.api.MaintenanceHttp;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0023 decision 3 for the writes that have no agent session: kb_scan without {@code episode}
 * opens its own {@code kind=scan} Episode (with it, the given one is stamped), and the user's REST
 * writes get a {@code kind=user} Episode. Reverting the scan keeps its Episode (decision 2).
 */
class EpisodeSourcesTest extends RunningServerTestBase {

    // Fake values of these tests only; SecretLeakIT owns the fixture's.
    private static final String PROD_VALUE = "kbtest-fake-4b1e9d07c2a6f358";
    private static final String LOCAL_VALUE = "kbtest-fake-a83f5c1e70d29b64";

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path cwd;

    private EpisodeProbe probe;
    private McpClient mcp;

    @BeforeEach
    void open() throws Exception {
        probe = new EpisodeProbe(port, store);
        mcp = McpClient.open(port);
    }

    @Test
    void a_scan_without_a_session_opens_its_own_scan_episode_which_revert_keeps() throws Exception {
        Path project =
                ScanProjects.springProject(scanRoot(), "zz-g12b-scan-own", PROD_VALUE, LOCAL_VALUE);

        String report = mcp.text("kb_scan", Map.of("path", project.toString()));

        String batch = EpisodeProbe.batch(report);
        List<String> stamps = probe.stamps(batch);
        assertThat(stamps).as(report).isNotEmpty();
        assertThat(stamps.stream().distinct()).as("one episode for the whole scan").hasSize(1);
        String episode = stamps.getFirst();
        assertThat(probe.episode(episode))
                .as("the stamp %s is an Episode", episode)
                .hasValueSatisfying(
                        props ->
                                assertThat(props)
                                        .containsEntry("kind", "scan")
                                        .containsEntry("client", "scan")
                                        .doesNotContainKeys("session_id", "batch"));

        HttpResponse<String> reverted =
                new MaintenanceHttp(port)
                        .post(MaintenanceHttp.REVERT, "{\"batch\": \"" + batch + "\"}");

        assertThat(reverted.statusCode()).as(reverted.body()).isEqualTo(200);
        assertThat(probe.episode(episode)).as("revert never deletes an Episode").isPresent();
    }

    @Test
    void a_scan_given_the_sessions_episode_stamps_that_one() throws Exception {
        Session session = probe.start("claude", newSessionId(), cwd);
        Path project =
                ScanProjects.springProject(
                        scanRoot(), "zz-g12b-scan-given", PROD_VALUE, LOCAL_VALUE);

        String report =
                mcp.text(
                        "kb_scan",
                        Map.of("path", project.toString(), "episode", session.episode()));

        assertThat(probe.stamps(EpisodeProbe.batch(report)))
                .as(report)
                .isNotEmpty()
                .containsOnly(session.episode());
    }

    /** {@code PUT /api/v1/secrets/<id>} is the user's own write (kb secret set). */
    @Test
    void a_rest_write_of_the_user_gets_a_user_episode() throws Exception {
        String entity = "zz-g12b-rest";
        String secret = entity + "/prod/db-password";
        mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        entity,
                        "type",
                        "database",
                        "facts",
                        List.of(Map.of("key", "engine", "value", "postgresql")),
                        "source",
                        Map.of("kind", "code")));

        HttpResponse<String> put = new SecretHttp(port).put(secret, "kbtest-fake-rest-0b7e2c19");

        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        List<Map<String, Object>> stamps =
                probe.rows(
                        "MATCH (:Entity {id: $entity})-[r:HAS_SECRET]->(s:Secret {id: $secret})"
                                + " RETURN s.episode AS node, r.episode AS edge",
                        Map.of("entity", entity, "secret", secret));
        assertThat(stamps)
                .singleElement()
                .satisfies(row -> assertThat(row.get("node")).isNotNull());
        String episode = (String) stamps.getFirst().get("node");
        assertThat(stamps.getFirst().get("edge")).isEqualTo(episode);
        assertThat(probe.episode(episode))
                .hasValueSatisfying(
                        props ->
                                assertThat(props)
                                        .containsEntry("kind", "user")
                                        .containsEntry("client", "kb"));
    }
}
