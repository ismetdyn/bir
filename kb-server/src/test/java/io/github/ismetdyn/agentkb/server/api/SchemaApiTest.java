package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/schema} (docs/CLIENTS.md §1): the vocabulary kb_schema reads from {@code
 * <repo>/schema}, so a client form never keeps its own copy.
 */
class SchemaApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private RepoSchema repoSchema;

    @Test
    void lists_every_entity_type_fact_key_and_relation_type_of_the_repo_schema() throws Exception {
        Schema schema = repoSchema.load();

        JsonNode answer = schema();

        assertThat(answer.get("entityTypes")).hasSize(schema.entityTypes().size());
        assertThat(answer.get("factKeys")).hasSize(schema.factKeys().size());
        assertThat(answer.get("relationTypes")).hasSize(schema.relations().size());
    }

    @Test
    void an_entity_type_has_its_label_and_whether_it_is_operational() throws Exception {
        JsonNode server = find(schema().get("entityTypes"), "key", "server");

        assertThat(server.get("label").asString()).isEqualTo("Server");
        assertThat(server.get("operational").asBoolean()).isTrue();
        assertThat(
                        find(schema().get("entityTypes"), "key", "person")
                                .get("operational")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void a_fact_key_carries_its_rules_as_the_write_path_applies_them() throws Exception {
        JsonNode keys = schema().get("factKeys");

        JsonNode engine = find(keys, "key", "engine");
        JsonNode port = find(keys, "key", "port");
        JsonNode role = find(keys, "key", "role");
        JsonNode hostname = find(keys, "key", "hostname");

        assertThat(engine.get("allowed").toString()).contains("\"postgresql\"", "\"oracle\"");
        assertThat(engine.get("multi").asBoolean()).isFalse();
        assertThat(port.get("validator").asString()).isEqualTo("port-spec");
        assertThat(port.get("multi").asBoolean()).isTrue();
        assertThat(port.get("target").asBoolean()).isTrue();
        // A text key is multi-valued without saying so (facts.yaml header).
        assertThat(role.get("kind").asString()).isEqualTo("text");
        assertThat(role.get("multi").asBoolean()).isTrue();
        assertThat(hostname.get("allowed")).isEmpty();
        assertThat(hostname.get("kind").isNull()).isTrue();
        assertThat(hostname.get("target").asBoolean()).isFalse();
    }

    @Test
    void a_relation_type_is_in_graph_case_with_its_meaning_and_synonyms() throws Exception {
        JsonNode runs = find(schema().get("relationTypes"), "type", "RUNS");

        assertThat(runs.get("description").asString()).isNotBlank();
        assertThat(runs.get("synonyms").toString()).isEqualTo("[\"HOSTS\"]");
    }

    private JsonNode schema() throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/schema");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        return list.valueStream()
                .filter(item -> item.get(field).asString().equals(value))
                .findFirst()
                .orElseThrow(() -> new AssertionError(field + " " + value + " not in " + list));
    }
}
