package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.CHECK;
import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.RESTORE;
import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.REVERT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The F2 maintenance endpoints (docs/CLIENTS.md §1, ADR-0022 decision 5), JSON in and out, no token
 * (ADR-0020):
 *
 * <ul>
 *   <li>{@code POST /api/v1/revert {"batch": id}} → 200 {@code {"batch", "deleted", "reopened",
 *       "deletedSecretValues"}}
 *   <li>{@code POST /api/v1/restore {"at"?: commit}} → 200 {@code {"nodes", "edges", "keptSecrets",
 *       "keptSignals", "droppedEdges", "droppedSecretValues"}}; without at, HEAD of {@code
 *       agentkb.repo}
 *   <li>{@code GET /api/v1/check} → 200 {@code {"clean": bool, "problems": [{"kind", "id",
 *       "detail"}]}}
 * </ul>
 *
 * A refusal is 400 {@code {"error", "hint"}}, never a 500 (RestErrorHandler). The context is shared
 * (RunningServerTestBase): a restore here brings the graph back to the seed, local data kept.
 */
class MaintenanceApiTest extends RunningServerTestBase {

    private static final String SEED_SECRET = "ornek-lb/prod/panel-admin";
    private static final String VALUE = "kbtest-FAKE-maintenance-2c7b9e40";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String ENTITY_COUNT = "MATCH (e:Entity {id: $id}) RETURN count(e) AS n";

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path elsewhere;

    /** Once reverted, the batch is unknown: the same request again is an error with a hint. */
    @Test
    void revert_of_a_batch_deletes_what_it_opened_and_a_second_one_is_refused() throws Exception {
        String batch = "kbtest-rest-" + UUID.randomUUID();
        String entity = write("kbtest-rest-revert-", batch);
        long opened =
                count(
                        "MATCH (n {batch: $id}) WITH count(n) AS nodes MATCH ()-[r {batch: $id}]->()"
                                + " WITH nodes, count(r) AS edges RETURN nodes + edges AS n",
                        batch);
        MaintenanceHttp api = new MaintenanceHttp(port);
        String request = "{\"batch\": \"" + batch + "\"}";

        HttpResponse<String> reply = api.post(REVERT, request);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(reply.body());
        assertThat(body.get("batch").asString()).isEqualTo(batch);
        assertThat(body.get("deleted").asLong()).isEqualTo(opened);
        assertThat(body.get("reopened").asInt()).isZero();
        assertThat(body.get("deletedSecretValues").asInt()).isZero();
        assertThat(count(ENTITY_COUNT, entity)).isZero();
        assertRefused(api.post(REVERT, request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"batch\": \"\"}", "{\"batch\": 7}", "not json", ""})
    void revert_without_a_usable_batch_is_a_client_error(String body) throws Exception {
        HttpResponse<String> reply = new MaintenanceHttp(port).post(REVERT, body);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
    }

    /**
     * A record written after the snapshot goes; a secret value set here stays (SPEC §10). The
     * answer counts what it restored and never holds the value.
     */
    @Test
    void restore_without_a_commit_rebuilds_head_and_keeps_secret_values() throws Exception {
        committedRepo();
        String entity = write("kbtest-rest-restore-", null);
        SecretHttp secrets = new SecretHttp(port);
        assertThat(secrets.put(SEED_SECRET, VALUE).statusCode()).as("fixture").isEqualTo(204);

        HttpResponse<String> reply = new MaintenanceHttp(port).post(RESTORE, "{}");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(reply.body()).doesNotContain(VALUE);
        JsonNode body = JSON.readTree(reply.body());
        assertThat(body.get("nodes").asInt()).isEqualTo(SnapshotRepos.seed().nodes().size());
        assertThat(body.get("edges").asInt()).isEqualTo(SnapshotRepos.seed().edges().size());
        for (String kept :
                List.of("keptSecrets", "keptSignals", "droppedEdges", "droppedSecretValues")) {
            assertThat(body.has(kept)).as(kept).isTrue();
        }
        assertThat(count(ENTITY_COUNT, entity)).isZero();
        assertThat(secrets.get(SEED_SECRET).body()).isEqualTo(VALUE);
    }

    @Test
    void restore_at_an_explicit_commit_reads_that_commit() throws Exception {
        String head = ScanProjects.git(committedRepo(), "rev-parse", "HEAD").strip();

        HttpResponse<String> reply =
                new MaintenanceHttp(port).post(RESTORE, "{\"at\": \"" + head + "\"}");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(JSON.readTree(reply.body()).get("nodes").asInt())
                .isEqualTo(SnapshotRepos.seed().nodes().size());
    }

    /** {@code at} goes to git; an option-shaped or unknown commit is refused, nothing runs. */
    @ParameterizedTest
    @ValueSource(strings = {"--output=%s", "--upload-pack=touch %s", "kbtest-no-such-commit"})
    void restore_at_an_unusable_commit_is_an_error_and_runs_nothing(String template)
            throws Exception {
        committedRepo();
        String entity = write("kbtest-rest-kept-", null);
        Path planted = elsewhere.resolve("planted");

        HttpResponse<String> reply =
                new MaintenanceHttp(port)
                        .post(
                                RESTORE,
                                JSON.writeValueAsString(Map.of("at", template.formatted(planted))));

        assertRefused(reply);
        assertThat(planted).doesNotExist();
        assertThat(count(ENTITY_COUNT, entity)).as("no restore ran").isOne();
    }

    /** After a restore the graph is the seed, whose one open conflict check must list. */
    @Test
    void check_lists_the_problems_of_the_graph() throws Exception {
        committedRepo();
        MaintenanceHttp api = new MaintenanceHttp(port);
        assertThat(api.post(RESTORE, "{}").statusCode()).as("restore the seed").isEqualTo(200);

        HttpResponse<String> reply = api.get(CHECK);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(reply.body());
        assertThat(body.get("clean").isBoolean()).isTrue();
        assertThat(body.get("clean").asBoolean()).isFalse();
        List<String> problems = new ArrayList<>();
        body.get("problems")
                .forEach(
                        problem -> {
                            assertThat(problem.has("detail")).isTrue();
                            problems.add(
                                    problem.get("kind").asString()
                                            + " "
                                            + problem.get("id").asString());
                        });
        assertThat(problems).contains("open-conflict " + SnapshotRepos.SEED_CONFLICT_FACT);
    }

    // The context is shared; leave the seed Secret as other tests expect it.
    @AfterEach
    void removeValue() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret {id: $id}) REMOVE s.secret_value",
                        Map.of("id", SEED_SECRET));
    }

    private static void assertRefused(HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        JsonNode body = JSON.readTree(reply.body());
        assertThat(body.get("error").asString()).isNotBlank();
        assertThat(body.get("hint").asString()).isNotBlank();
    }

    /**
     * A new server {@code <prefix><uuid>} with one fact; {@code batch} null for a batch of its own.
     */
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
        assertThat(count(ENTITY_COUNT, entity)).as("precondition").isOne();
        return entity;
    }

    private long count(String cypher, String id) {
        return store.read(tx -> (Long) tx.execute(cypher, Map.of("id", id)).next().get("n"));
    }
}
