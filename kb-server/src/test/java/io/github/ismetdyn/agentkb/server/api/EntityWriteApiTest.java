package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The new writes of docs/CLIENTS.md §1 (ADR-0025 decision 9): {@code POST /entities} and {@code
 * POST /entities/{id}/facts} are the user's (Source.USER), one batch per request, with no Episode.
 * A refused write is 400 {@code {error, hint}} without the request value.
 */
class EntityWriteApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void post_entities_creates_a_user_entity_in_a_batch_of_its_own() throws Exception {
        String id = StoredRecords.id();

        HttpResponse<String> reply =
                http().post(
                                "/api/v1/entities",
                                body(
                                        Map.of(
                                                "id",
                                                id,
                                                "type",
                                                "server",
                                                "facts",
                                                List.of(fact("os", "rhel 9")))));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        JsonNode answer = JSON.readTree(reply.body());
        assertThat(answer.get("entity").asString()).isEqualTo(id);
        assertThat(answer.get("created").asBoolean()).isTrue();
        Map<String, Object> node =
                StoredRecords.node(store, "MATCH (e:Server {id: $id}) RETURN e", id);
        assertThat(node).containsEntry("batch", answer.get("batch").asString());
        assertThat(node).doesNotContainKey("episode");
    }

    @Test
    void post_entities_on_a_known_id_writes_to_it_and_says_it_is_not_new() throws Exception {
        String id = StoredRecords.id();
        http().post(
                        "/api/v1/entities",
                        body(
                                Map.of(
                                        "id",
                                        id,
                                        "type",
                                        "server",
                                        "facts",
                                        List.of(fact("os", "rhel 9")))));

        HttpResponse<String> again =
                http().post(
                                "/api/v1/entities",
                                body(
                                        Map.of(
                                                "id",
                                                id,
                                                "type",
                                                "server",
                                                "facts",
                                                List.of(fact("os", "rhel 9")))));

        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(JSON.readTree(again.body()).get("created").asBoolean()).isFalse();
    }

    @Test
    void post_entities_refuses_an_unknown_type_without_the_id() throws Exception {
        String id = StoredRecords.id();

        assertRefused(
                http().post("/api/v1/entities", body(Map.of("id", id, "type", "zzh6-no-type"))),
                400,
                id,
                "zzh6-no-type");
        assertRefused(http().post("/api/v1/entities", body(Map.of("id", id))), 400, id);
    }

    /** Lead H6 review 8: kb add <tür> <id> opens the entity alone, one batch, revertible. */
    @Test
    void post_entities_without_facts_opens_the_entity_alone_and_reverts() throws Exception {
        String id = StoredRecords.id();

        HttpResponse<String> reply =
                http().post("/api/v1/entities", body(Map.of("id", id, "type", "server")));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        String batch = JSON.readTree(reply.body()).get("batch").asString();
        assertThat(StoredRecords.node(store, "MATCH (e:Server {id: $id}) RETURN e", id))
                .containsEntry("batch", batch);
        HttpResponse<String> reverted =
                new MaintenanceHttp(port)
                        .post(MaintenanceHttp.REVERT, body(Map.of("batch", batch)));
        assertThat(reverted.statusCode()).as(reverted.body()).isEqualTo(200);
        assertThat(
                        StoredRecords.rows(
                                store, "MATCH (e:Entity {id: $id}) RETURN e", Map.of("id", id)))
                .isEmpty();
    }

    @Test
    void facts_are_the_users_in_one_batch_and_a_new_value_closes_the_old() throws Exception {
        String id = StoredRecords.server(writes);
        String path = "/api/v1/entities/" + id + "/facts";
        http().post(path, body(Map.of("facts", List.of(fact("os", "rhel 8")))));

        HttpResponse<String> reply =
                http().post(
                                path,
                                body(
                                        Map.of(
                                                "facts",
                                                List.of(
                                                        fact("os", "rhel 9"),
                                                        fact("version", "9.4")))));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode answer = JSON.readTree(reply.body());
        assertThat(answer.get("added")).hasSize(2);
        assertThat(answer.get("closed")).hasSize(1);
        List<Map<String, Object>> added =
                StoredRecords.rows(
                        store,
                        "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                                + " RETURN f",
                        Map.of("id", id));
        assertThat(added).hasSize(3).allSatisfy(f -> assertThat(f).containsEntry("source", "user"));
        assertThat(
                        StoredRecords.rows(
                                store,
                                "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact)"
                                        + " WHERE f.batch = $batch RETURN f",
                                Map.of("id", id, "batch", answer.get("batch").asString())))
                .hasSize(2)
                .allSatisfy(f -> assertThat(f).doesNotContainKey("episode"));
    }

    @Test
    void a_secret_looking_value_is_refused_without_echo() throws Exception {
        String id = StoredRecords.server(writes);
        String value = "password=zzh6" + UUID.randomUUID();

        assertRefused(
                http().post(
                                "/api/v1/entities/" + id + "/facts",
                                body(Map.of("facts", List.of(fact("note", value))))),
                400,
                value);
    }

    @Test
    void facts_on_an_unknown_entity_are_refused() throws Exception {
        String id = StoredRecords.id();

        assertRefused(
                http().post(
                                "/api/v1/entities/" + id + "/facts",
                                body(Map.of("facts", List.of(fact("os", "rhel 9"))))),
                400,
                id);
        assertThat(
                        StoredRecords.rows(
                                store, "MATCH (e:Entity {id: $id}) RETURN e", Map.of("id", id)))
                .isEmpty();
    }

    private static String body(Map<String, ?> fields) {
        return JSON.writeValueAsString(fields);
    }

    private ApiHttp http() {
        return new ApiHttp(port);
    }

    private static Map<String, String> fact(String key, String value) {
        return Map.of("key", key, "value", value);
    }
}
