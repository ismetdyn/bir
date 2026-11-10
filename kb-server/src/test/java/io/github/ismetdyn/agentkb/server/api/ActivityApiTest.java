package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static io.github.ismetdyn.agentkb.server.api.ApiHttp.q;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/activity?source=&batch=&since=&limit=} (docs/CLIENTS.md §1): the latest
 * batches newest first, 50 by default and 500 at most; each is one {@code kb revert --batch}.
 */
class ActivityApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void a_rest_write_is_one_user_batch_naming_its_entity() throws Exception {
        String id = StoredRecords.id();
        String batch = created(id);

        JsonNode batches = activity("?batch=" + batch).get("batches");

        assertThat(batches).hasSize(1);
        JsonNode one = batches.get(0);
        assertThat(one.get("batch").asString()).isEqualTo(batch);
        assertThat(one.get("sources").toString()).isEqualTo("[\"user\"]");
        assertThat(one.get("opened").asInt()).isPositive();
        assertThat(one.get("entities").toString()).contains(id);
    }

    @Test
    void a_close_is_a_user_batch_that_closed_one_record() throws Exception {
        String id = StoredRecords.id();
        created(id);
        String fact = entityFactId(id);
        HttpResponse<String> closed =
                new ApiHttp(port).post("/api/v1/facts/" + fact + "/close", "{}");
        String batch = JSON.readTree(closed.body()).get("batch").asString();

        JsonNode one = activity("?batch=" + batch).get("batches").get(0);

        assertThat(one.get("closed").asInt()).isOne();
        assertThat(one.get("opened").asInt()).isZero();
        assertThat(one.get("sources").toString()).isEqualTo("[\"user\"]");
    }

    @Test
    void newest_first_and_filtered_by_source_and_since() throws Exception {
        String before = Instant.now().minusSeconds(1).toString();
        String batch = created(StoredRecords.id());

        JsonNode latest = activity("?since=" + q(before)).get("batches");
        JsonNode scans = activity("?source=scan&limit=500").get("batches");
        JsonNode future = activity("?since=" + q("2999-01-01T00:00:00Z")).get("batches");

        assertThat(latest.toString()).contains(batch);
        assertThat(scans.toString()).doesNotContain(batch);
        assertThat(future).isEmpty();
    }

    @Test
    void the_limit_is_50_by_default_and_counts_the_rest() throws Exception {
        // The seed has no batches; two of its own make the counts below hold in any class order.
        created(StoredRecords.id());
        created(StoredRecords.id());
        JsonNode one = activity("?limit=1");
        JsonNode two = activity("?limit=2");

        assertThat(one.get("batches")).hasSize(1);
        assertThat(activity("").get("batches").size()).isLessThanOrEqualTo(50);
        assertThat(one.get("more").asInt()).isEqualTo(two.get("more").asInt() + 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?limit=501",
                "?limit=0",
                "?since=yesterday",
                "?source=robot",
                "?batch=a%20b"
            })
    void a_bad_filter_or_limit_is_refused(String query) throws Exception {
        assertRefused(new ApiHttp(port).get("/api/v1/activity" + query), 400, "yesterday", "robot");
    }

    /** A new user server with one fact, by POST /entities; its batch. */
    private String created(String id) throws Exception {
        HttpResponse<String> reply =
                new ApiHttp(port)
                        .post(
                                "/api/v1/entities",
                                "{\"id\": \""
                                        + id
                                        + "\", \"type\": \"server\", \"facts\": [{\"key\":"
                                        + " \"os\", \"value\": \"rhel 9\"}]}");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        return JSON.readTree(reply.body()).get("batch").asString();
    }

    private String entityFactId(String id) throws Exception {
        JsonNode entity = JSON.readTree(new ApiHttp(port).get("/api/v1/entities/" + id).body());
        return entity.get("facts").get(0).get("id").asString();
    }

    private JsonNode activity(String query) throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/activity" + query);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }
}
