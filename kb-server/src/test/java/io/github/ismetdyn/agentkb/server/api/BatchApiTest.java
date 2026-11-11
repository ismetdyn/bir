package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/activity/{batch}?limit=} (docs/CLIENTS.md §1): what one batch opened, closed
 * and revised, record by record, and whether kb revert would undo it now.
 */
class BatchApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String NOTE = "{\"facts\": [{\"key\": \"note\", \"value\": \"%s\"}]}";

    @Test
    void a_new_entity_batch_lists_the_entity_and_its_fact_and_is_revertible() throws Exception {
        String id = StoredRecords.id();
        String batch = created(id);

        JsonNode answer = batch(batch, "");

        assertThat(answer.get("batch").asString()).isEqualTo(batch);
        assertThat(answer.get("sources").toString()).isEqualTo("[\"user\"]");
        assertThat(answer.get("at").asString()).isNotBlank();
        assertThat(answer.get("revertible").asBoolean()).isTrue();
        assertThat(answer.get("blockedBy")).isEmpty();
        JsonNode entity = change(answer, "opened", "entity");
        assertThat(entity.get("id").asString()).isEqualTo(id);
        assertThat(entity.get("key").asString()).isEqualTo("Server");
        JsonNode fact = change(answer, "opened", "fact");
        assertThat(fact.get("entity").asString()).isEqualTo(id);
        assertThat(fact.get("key").asString()).isEqualTo("os");
        assertThat(fact.get("value").asString()).isEqualTo("rhel 9");
        assertThat(fact.get("source").asString()).isEqualTo("user");
        assertThat(answer.get("more").asInt()).isZero();
    }

    @Test
    void a_later_batch_on_the_same_entity_blocks_the_revert_and_is_named() throws Exception {
        String id = StoredRecords.id();
        String first = created(id);
        String later =
                posted("/api/v1/entities/" + id + "/facts", NOTE.formatted("kbtest later"), 200);

        JsonNode blocked = batch(first, "");

        assertThat(blocked.get("revertible").asBoolean()).isFalse();
        assertThat(blocked.get("blockedBy").toString()).isEqualTo("[\"" + later + "\"]");
        assertThat(batch(later, "").get("revertible").asBoolean()).isTrue();
    }

    @Test
    void a_close_a_relation_and_a_summary_edit_show_as_closed_opened_and_revised()
            throws Exception {
        String id = StoredRecords.id();
        created(id);
        String fact =
                JSON.readTree(new ApiHttp(port).get("/api/v1/entities/" + id).body())
                        .get("facts")
                        .get(0)
                        .get("id")
                        .asString();

        JsonNode closed = batch(posted("/api/v1/facts/" + fact + "/close", "{}", 200), "");
        JsonNode related =
                batch(
                        posted(
                                "/api/v1/entities/" + id + "/relations",
                                "{\"relations\": [{\"type\": \"behind\", \"target\":"
                                        + " \"ornek-lb\"}]}",
                                200),
                        "");
        HttpResponse<String> patched =
                new ApiHttp(port).patch("/api/v1/entities/" + id, "{\"summary\": \"kbtest\"}");
        JsonNode revised = batch(JSON.readTree(patched.body()).get("batch").asString(), "");

        assertThat(change(closed, "closed", "fact").get("id").asString()).isEqualTo(fact);
        JsonNode relation = change(related, "opened", "relation");
        assertThat(relation.get("key").asString()).isEqualTo("BEHIND");
        assertThat(relation.get("value").asString()).isEqualTo("ornek-lb");
        assertThat(relation.get("entity").asString()).isEqualTo(id);
        JsonNode revision = change(revised, "revised", "entity");
        assertThat(revision.get("id").asString()).isEqualTo(id);
        assertThat(revision.get("key").asString()).isEqualTo("summary");
    }

    @Test
    void a_long_value_is_one_capped_line() throws Exception {
        String id = StoredRecords.id();
        created(id);
        String batch =
                posted("/api/v1/entities/" + id + "/facts", NOTE.formatted("x".repeat(480)), 200);

        String value = change(batch(batch, ""), "opened", "fact").get("value").asString();

        // As a search snippet (SearchApiTest): 300 characters and the ellipsis.
        assertThat(value).hasSizeLessThanOrEqualTo(301).endsWith("…").doesNotContain("\n");
    }

    @Test
    void the_limit_cuts_the_changes_and_counts_the_rest() throws Exception {
        String batch = created(StoredRecords.id());

        JsonNode one = batch(batch, "?limit=1");

        assertThat(one.get("changes")).hasSize(1);
        assertThat(one.get("more").asInt()).isEqualTo(batch(batch, "").get("changes").size() - 1);
        assertRefused(new ApiHttp(port).get("/api/v1/activity/" + batch + "?limit=501"), 400);
    }

    @Test
    void an_unknown_batch_is_404_and_a_malformed_one_400_without_echo() throws Exception {
        assertRefused(
                new ApiHttp(port).get("/api/v1/activity/kbtest-no-such-batch"),
                404,
                "kbtest-no-such-batch");
        assertRefused(new ApiHttp(port).get("/api/v1/activity/kbtest%20bad"), 400, "kbtest");
    }

    private String created(String id) throws Exception {
        return posted(
                "/api/v1/entities",
                "{\"id\": \""
                        + id
                        + "\", \"type\": \"server\", \"facts\": [{\"key\": \"os\","
                        + " \"value\": \"rhel 9\"}]}",
                201);
    }

    private String posted(String path, String body, int status) throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).post(path, body);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(status);
        return JSON.readTree(reply.body()).get("batch").asString();
    }

    private JsonNode batch(String batch, String query) throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/activity/" + batch + query);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }

    private static JsonNode change(JsonNode answer, String change, String kind) {
        return answer.get("changes")
                .valueStream()
                .filter(c -> c.get("change").asString().equals(change))
                .filter(c -> c.get("kind").asString().equals(kind))
                .findFirst()
                .orElseThrow(() -> new AssertionError(change + " " + kind + " not in " + answer));
    }
}
