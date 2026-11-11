package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/entities?type=&limit=} (docs/CLIENTS.md §1): every entity without a search,
 * archived ones last and marked (invariant 12), 50 by default and 500 at most.
 */
class EntityListApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void lists_an_entity_with_its_type_status_summary_and_last_write() throws Exception {
        String id = created("server");

        JsonNode entity = find(list("?type=server&limit=500"), id);

        assertThat(entity.get("type").asString()).isEqualTo("Server");
        assertThat(entity.get("status").asString()).isEqualTo("current");
        assertThat(entity.get("summary").asString()).isEqualTo("kbtest summary");
        assertThat(entity.get("updatedAt").asString()).isNotBlank();
    }

    @Test
    void archived_entities_come_last_and_are_marked_not_hidden() throws Exception {
        String archived = created("server");
        assertThat(
                        new ApiHttp(port)
                                .post("/api/v1/entities/" + archived + "/archive", "{}")
                                .statusCode())
                .isEqualTo(200);
        created("server");

        List<String> statuses =
                list("?type=server&limit=500")
                        .get("entities")
                        .valueStream()
                        .map(e -> e.get("status").asString())
                        .toList();

        assertThat(find(list("?type=server&limit=500"), archived).get("status").asString())
                .isEqualTo("archived");
        assertThat(statuses.subList(statuses.indexOf("archived"), statuses.size()))
                .containsOnly("archived");
    }

    @Test
    void the_type_filter_takes_the_type_in_any_case_and_keeps_other_types_out() throws Exception {
        String server = created("server");
        String person = created("person");

        String lower = list("?type=server&limit=500").toString();
        String label = list("?type=Server&limit=500").toString();

        assertThat(lower).contains(server).doesNotContain(person).doesNotContain("\"Person\"");
        assertThat(label).contains(server);
        assertThat(list("?limit=500").toString()).contains(server, person);
    }

    @Test
    void the_limit_is_50_by_default_and_counts_the_rest() throws Exception {
        JsonNode one = list("?limit=1");
        JsonNode two = list("?limit=2");

        assertThat(one.get("entities")).hasSize(1);
        assertThat(list("").get("entities").size()).isLessThanOrEqualTo(50);
        assertThat(one.get("more").asInt()).isEqualTo(two.get("more").asInt() + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=501", "?limit=many", "?type=a%0Ab"})
    void a_bad_limit_or_type_is_refused(String query) throws Exception {
        assertRefused(new ApiHttp(port).get("/api/v1/entities" + query), 400, "many");
    }

    private String created(String type) throws Exception {
        String id = StoredRecords.id();
        HttpResponse<String> reply =
                new ApiHttp(port)
                        .post(
                                "/api/v1/entities",
                                "{\"id\": \"" + id + "\", \"type\": \"" + type + "\"}");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        HttpResponse<String> summary =
                new ApiHttp(port)
                        .patch("/api/v1/entities/" + id, "{\"summary\": \"kbtest summary\"}");
        assertThat(summary.statusCode()).as(summary.body()).isEqualTo(200);
        return id;
    }

    private JsonNode list(String query) throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/entities" + query);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }

    private static JsonNode find(JsonNode answer, String id) {
        return answer.get("entities")
                .valueStream()
                .filter(e -> e.get("id").asString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " not in " + answer));
    }
}
