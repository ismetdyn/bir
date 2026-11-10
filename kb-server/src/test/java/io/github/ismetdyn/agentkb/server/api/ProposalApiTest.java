package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.ProposalRequest;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/proposals}, {@code POST /proposals/{id}/accept} and {@code …/reject {note?}}
 * (ADR-0025 decisions 12 and 18): open proposals first; a review sets the status, a reject note is
 * kept; only an open proposal is reviewed.
 */
class ProposalApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String PROPOSAL = "MATCH (p:Proposal {id: $id}) RETURN p";

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void proposals_lists_open_ones_before_reviewed_ones() throws Exception {
        String reviewed = propose();
        new ApiHttp(port).post("/api/v1/proposals/" + reviewed + "/reject", "{}");
        String open = propose();

        JsonNode proposals = list().get("proposals");

        String text = proposals.toString();
        assertThat(text).contains(open).contains(reviewed);
        assertThat(text.indexOf(open)).isLessThan(text.indexOf(reviewed));
        assertThat(proposals.get(0).get("status").asString()).isEqualTo("open");
    }

    @Test
    void accept_sets_the_status() throws Exception {
        String id = propose();

        HttpResponse<String> reply =
                new ApiHttp(port).post("/api/v1/proposals/" + id + "/accept", "{}");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(StoredRecords.node(store, PROPOSAL, id)).containsEntry("status", "accepted");
        assertRefused(new ApiHttp(port).post("/api/v1/proposals/" + id + "/accept", "{}"), 400);
    }

    @Test
    void reject_keeps_the_note() throws Exception {
        String id = propose();

        HttpResponse<String> reply =
                new ApiHttp(port)
                        .post("/api/v1/proposals/" + id + "/reject", "{\"note\": \"gerek yok\"}");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(StoredRecords.node(store, PROPOSAL, id))
                .containsEntry("status", "rejected")
                .containsEntry("review_note", "gerek yok");
        assertThat(list().toString()).contains("\"reviewNote\":\"gerek yok\"");
    }

    @Test
    void a_secret_looking_note_is_refused_without_echo() throws Exception {
        String id = propose();
        String note = "password=zzh6" + UUID.randomUUID();

        assertRefused(
                new ApiHttp(port)
                        .post(
                                "/api/v1/proposals/" + id + "/reject",
                                JSON.writeValueAsString(Map.of("note", note))),
                400,
                note);
        assertThat(StoredRecords.node(store, PROPOSAL, id)).containsEntry("status", "open");
    }

    private String propose() {
        return writes.propose(
                new ProposalRequest(
                        Source.AGENT,
                        "rule-change",
                        null,
                        "zz-h6 rule " + UUID.randomUUID(),
                        "zz-h6 evidence"));
    }

    private JsonNode list() throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/proposals?limit=500");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }
}
