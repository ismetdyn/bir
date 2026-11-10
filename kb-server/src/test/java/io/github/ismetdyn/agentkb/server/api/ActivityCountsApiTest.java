package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.write.ProposalRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one activity row counts (lead H6 review 2): a write that replaces a value closed the old one
 * (SUPERSEDES), as WriteAnswer.closed says; an edit in place revised a record (Revision).
 */
class ActivityCountsApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private WriteService writes;

    @Test
    void a_write_that_replaces_a_value_counts_the_closed_one() throws Exception {
        String id = StoredRecords.server(writes);
        String facts = "/api/v1/entities/" + id + "/facts";
        post(facts, "{\"facts\": [{\"key\": \"os\", \"value\": \"rhel 8\"}]}");

        JsonNode answer = post(facts, "{\"facts\": [{\"key\": \"os\", \"value\": \"rhel 9\"}]}");

        assertThat(answer.get("closed")).hasSize(1);
        JsonNode row = batch(answer.get("batch").asString());
        assertThat(row.get("closed").asInt()).isOne();
        assertThat(row.get("opened").asInt()).isPositive();
    }

    @Test
    void patch_and_archive_count_a_revision() throws Exception {
        String id = StoredRecords.server(writes);

        JsonNode patched = patch("/api/v1/entities/" + id, "{\"summary\": \"zz-h6 revised\"}");
        JsonNode archived = post("/api/v1/entities/" + id + "/archive", "{}");

        assertThat(batch(patched.get("batch").asString()).get("revised").asInt()).isPositive();
        assertThat(batch(archived.get("batch").asString()).get("revised").asInt()).isPositive();
    }

    @Test
    void accepting_a_proposal_counts_a_revision() throws Exception {
        String proposal =
                writes.propose(
                        new ProposalRequest(
                                Source.AGENT, "rule-change", null, "zz-h6 activity rule", null));

        JsonNode accepted = post("/api/v1/proposals/" + proposal + "/accept", "{}");

        assertThat(batch(accepted.get("batch").asString()).get("revised").asInt()).isPositive();
    }

    /**
     * Accept-new is no edit in place: it closes the waiting and the current value (closed_batch and
     * SUPERSEDES alike), counted once each, and opens the user's.
     */
    @Test
    void accept_new_counts_both_closed_values_once() throws Exception {
        String id = "zz-h6-a-" + UUID.randomUUID().toString().substring(0, 8);
        for (Source source : List.of(Source.USER, Source.AGENT)) {
            writes.write(
                    new WriteRequest(
                            source,
                            id,
                            "server",
                            List.of(new FactInput("os", "rhel " + source.trust(), "prod", null)),
                            List.of(),
                            null,
                            null,
                            Instant.now()));
        }
        String waiting = waitingFact(id);

        JsonNode resolved =
                post(
                        "/api/v1/conflicts/" + waiting + "/resolve",
                        "{\"resolution\": \"accept-new\"}");

        JsonNode row = batch(resolved.get("batch").asString());
        assertThat(row.get("closed").asInt()).isEqualTo(2);
        assertThat(row.get("opened").asInt()).isPositive();
    }

    private String waitingFact(String id) throws Exception {
        for (JsonNode fact :
                JSON.readTree(new ApiHttp(port).get("/api/v1/entities/" + id).body())
                        .get("facts")) {
            if (fact.get("status").asString().equals("conflict")) {
                return fact.get("id").asString();
            }
        }
        throw new AssertionError("no waiting value on " + id);
    }

    private JsonNode batch(String batch) throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/activity?batch=" + batch);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode batches = JSON.readTree(reply.body()).get("batches");
        assertThat(batches).as(reply.body()).hasSize(1);
        return batches.get(0);
    }

    private JsonNode post(String path, String body) throws Exception {
        return ok(new ApiHttp(port).post(path, body));
    }

    private JsonNode patch(String path, String body) throws Exception {
        return ok(new ApiHttp(port).patch(path, body));
    }

    private static JsonNode ok(HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }
}
