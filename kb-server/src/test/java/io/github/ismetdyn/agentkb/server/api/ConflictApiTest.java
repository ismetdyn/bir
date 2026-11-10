package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.time.Instant;
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
 * {@code GET /api/v1/conflicts} and {@code POST /conflicts/{id}/resolve} (ADR-0025 decision 11):
 * the waiting value next to the current one; accept-new makes it the user's, keep-old closes it.
 */
class ConflictApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String OPEN_HOSTNAMES =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact {key: 'hostname'})"
                    + " WHERE f.valid_to IS NULL RETURN f";

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void conflicts_lists_the_waiting_value_next_to_the_current_one() throws Exception {
        String id = disputed();

        JsonNode conflict = conflictOf(id);

        assertThat(conflict.get("value").asString()).isEqualTo(id + "-agent.ornek.example");
        assertThat(conflict.get("source").asString()).isEqualTo("agent");
        assertThat(conflict.get("key").asString()).isEqualTo("hostname");
        assertThat(conflict.get("current").toString()).contains(id + "-user.ornek.example");
    }

    @Test
    void keep_old_closes_the_waiting_value_only() throws Exception {
        String id = disputed();
        String waiting = conflictOf(id).get("id").asString();

        HttpResponse<String> reply = resolve(waiting, "keep-old");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(StoredRecords.rows(store, OPEN_HOSTNAMES, Map.of("id", id)))
                .singleElement()
                .satisfies(f -> assertThat(f).containsEntry("value", id + "-user.ornek.example"));
        assertThat(conflicts().toString()).doesNotContain(waiting);
    }

    @Test
    void accept_new_makes_the_waiting_value_the_users() throws Exception {
        String id = disputed();

        HttpResponse<String> reply = resolve(conflictOf(id).get("id").asString(), "accept-new");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(StoredRecords.rows(store, OPEN_HOSTNAMES, Map.of("id", id)))
                .singleElement()
                .satisfies(
                        f ->
                                assertThat(f)
                                        .containsEntry("value", id + "-agent.ornek.example")
                                        .containsEntry("source", "user"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"resolution\": \"zzh6-maybe\"}", "{}"})
    void an_unknown_resolution_is_refused_without_echo(String body) throws Exception {
        String waiting = conflictOf(disputed()).get("id").asString();

        assertRefused(
                new ApiHttp(port).post("/api/v1/conflicts/" + waiting + "/resolve", body),
                400,
                "zzh6-maybe");
    }

    @Test
    void conflicts_refuses_a_limit_over_500() throws Exception {
        assertRefused(new ApiHttp(port).get("/api/v1/conflicts?limit=501"), 400);
    }

    /** A user hostname, then an agent's other one, which waits as a conflict. */
    private String disputed() {
        String id = "zz-h6-c-" + UUID.randomUUID().toString().substring(0, 8);
        for (Source source : List.of(Source.USER, Source.AGENT)) {
            writes.write(
                    new WriteRequest(
                            source,
                            id,
                            "server",
                            List.of(
                                    new FactInput(
                                            "hostname",
                                            id
                                                    + "-"
                                                    + source.name()
                                                            .toLowerCase(java.util.Locale.ROOT)
                                                    + ".ornek.example",
                                            "prod",
                                            null)),
                            List.of(),
                            null,
                            null,
                            Instant.now()));
        }
        return id;
    }

    private JsonNode conflictOf(String id) throws Exception {
        for (JsonNode conflict : conflicts().get("conflicts")) {
            if (conflict.get("entity").asString().equals(id)) {
                return conflict;
            }
        }
        throw new AssertionError("no conflict of " + id);
    }

    private JsonNode conflicts() throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/conflicts?limit=500");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }

    private HttpResponse<String> resolve(String id, String resolution) throws Exception {
        return new ApiHttp(port)
                .post(
                        "/api/v1/conflicts/" + id + "/resolve",
                        "{\"resolution\": \"" + resolution + "\"}");
    }
}
