package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.RESTORE;
import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.REVERT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the maintenance endpoints refuse before they act (MaintenanceApiTest has the contract): a
 * batch or commit name beyond the length and charset limits of the REST boundary (lead decision 3),
 * and a commit whose snapshot cannot become a graph (lead decision 5). Each is 400 {@code {error,
 * hint}}, never a 500, and the graph stays as it was. Each input is one the endpoint would
 * otherwise act on, so only the limit stands between the request and the change.
 */
class MaintenanceInputTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String ENTITY_COUNT = "MATCH (e:Entity {id: $id}) RETURN count(e) AS n";
    // Far beyond any batch (a ULID is 26) or commit name (a sha-256 hash is 64) the limit admits.
    private static final int OVER_LONG = 1_000;
    // A refusal may name the limit, never repeat this much of the request value.
    private static final int LONGEST_ECHO = 64;

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    /** A record really carries the over-long batch: WriteService takes any batch. */
    @Test
    void revert_of_an_over_long_batch_is_refused_and_deletes_nothing() throws Exception {
        String batch = "kbtest-rest-" + "x".repeat(OVER_LONG);
        String entity = write("kbtest-rest-long-batch-", batch);

        HttpResponse<String> reply =
                new MaintenanceHttp(port)
                        .post(REVERT, JSON.writeValueAsString(Map.of("batch", batch)));

        assertRefused(reply);
        assertThat(reply.body()).doesNotContain("x".repeat(LONGEST_ECHO));
        assertThat(count(entity)).as("nothing reverted").isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0001", "\n", "\u001b[2J"})
    void revert_of_a_batch_with_a_control_character_is_refused_and_deletes_nothing(String control)
            throws Exception {
        String batch = "kbtest-rest-" + control + "-" + UUID.randomUUID();
        String entity = write("kbtest-rest-control-batch-", batch);

        HttpResponse<String> reply =
                new MaintenanceHttp(port)
                        .post(REVERT, JSON.writeValueAsString(Map.of("batch", batch)));

        assertRefused(reply);
        assertThat(count(entity)).as("nothing reverted").isOne();
    }

    /** The name is a valid one git resolves to HEAD ({@code HEAD~0~0…}). */
    @Test
    void restore_at_an_over_long_commit_name_is_refused_before_git_runs() throws Exception {
        committedRepo();
        String entity = write("kbtest-rest-long-at-", null);
        String at = "HEAD" + "~0".repeat(OVER_LONG);

        HttpResponse<String> reply =
                new MaintenanceHttp(port).post(RESTORE, JSON.writeValueAsString(Map.of("at", at)));

        assertRefused(reply);
        assertThat(count(entity)).as("no restore ran").isOne();
    }

    /**
     * The names are valid ones git resolves: the commit whose message matches {@code seed[<control>
     * ]snapshot}, which committedRepo made.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u0001", "\n"})
    void restore_at_a_commit_name_with_a_control_character_is_refused_before_git_runs(
            String control) throws Exception {
        committedRepo();
        String entity = write("kbtest-rest-control-at-", null);
        String at = "HEAD^{/seed[" + control + " ]snapshot}";

        HttpResponse<String> reply =
                new MaintenanceHttp(port).post(RESTORE, JSON.writeValueAsString(Map.of("at", at)));

        assertRefused(reply);
        assertThat(count(entity)).as("no restore ran").isOne();
    }

    /**
     * A commit whose snapshot has an edge to a node it lacks. HEAD is the seed again afterwards, as
     * the other tests of the shared context expect.
     */
    @Test
    void restore_of_a_snapshot_that_cannot_become_a_graph_is_a_client_error() throws Exception {
        Path repo = committedRepo();
        Snapshot seed = SnapshotRepos.seed();
        List<Edge> edges = new ArrayList<>(seed.edges());
        edges.add(
                new Edge(
                        "01KBTESTORPHANEDGE00000002",
                        "DEPENDS_ON",
                        "ornek-app-01",
                        "kbtest-ghost",
                        Map.of()));
        SnapshotRepos.commit(repo, new Snapshot(seed.nodes(), edges), "kbtest broken snapshot");
        String broken = ScanProjects.git(repo, "rev-parse", "HEAD").strip();
        SnapshotRepos.commit(repo, seed, "kbtest seed snapshot again");
        String entity = write("kbtest-rest-broken-", null);

        HttpResponse<String> reply =
                new MaintenanceHttp(port)
                        .post(RESTORE, JSON.writeValueAsString(Map.of("at", broken)));

        assertRefused(reply);
        assertThat(JSON.readTree(reply.body()).has("trace_id")).as("not a 500").isFalse();
        assertThat(count(entity)).as("the graph is unchanged").isOne();
    }

    private static void assertRefused(HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        JsonNode body = JSON.readTree(reply.body());
        assertThat(body.get("error").asString()).isNotBlank();
        assertThat(body.get("hint").asString()).isNotBlank();
    }

    /** A new server {@code <prefix><uuid>} with one fact; {@code batch} null for its own. */
    private String write(String prefix, String batch) {
        String entity = prefix + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        entity,
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        batch,
                        Instant.now()));
        assertThat(count(entity)).as("precondition").isOne();
        return entity;
    }

    private long count(String entity) {
        return store.read(
                tx -> (Long) tx.execute(ENTITY_COUNT, Map.of("id", entity)).next().get("n"));
    }
}
