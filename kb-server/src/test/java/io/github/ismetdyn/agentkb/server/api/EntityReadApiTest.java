package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.MarkdownView;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import io.github.ismetdyn.agentkb.server.write.WriteResult;
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
 * {@code GET /api/v1/entities/{id}} (ADR-0025 decision 13): Markdown from MarkdownView, the one
 * renderer, or JSON with the ids the close endpoints take; an unknown id is 404 without it.
 */
class EntityReadApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private WriteService writes;
    @Autowired private KnowledgeQueries queries;

    /** ADR-0025 decision 13: the Markdown is MarkdownView's, character for character. */
    @Test
    void entity_as_markdown_is_the_markdown_view() throws Exception {
        HttpResponse<String> reply = http().get("/api/v1/entities/ornek-app-01", ApiHttp.MARKDOWN);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(reply.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith(ApiHttp.MARKDOWN));
        assertThat(reply.body())
                .isEqualTo(MarkdownView.render(queries.entity("ornek-app-01").orElseThrow()));
    }

    /** The JSON view carries the ids kb fact close and kb rel close take. */
    @Test
    void entity_as_json_carries_fact_and_relation_ids() throws Exception {
        String id = "zz-h6-read-" + UUID.randomUUID();
        String target = StoredRecords.server(writes);
        WriteResult written =
                writes.write(
                        new WriteRequest(
                                Source.USER,
                                id,
                                "server",
                                List.of(new FactInput("os", "rhel 9", "prod", null)),
                                List.of(new RelationInput("behind", target, "prod")),
                                null,
                                null,
                                Instant.now()));

        JsonNode entity = json(http().get("/api/v1/entities/" + id));

        assertThat(entity.get("id").asString()).isEqualTo(id);
        assertThat(entity.get("facts").get(0).get("id").asString())
                .isEqualTo(written.added().getFirst());
        assertThat(entity.get("facts").get(0).get("source").asString()).isEqualTo("user");
        JsonNode relation = entity.get("relations").get(0);
        assertThat(relation.get("type").asString()).isEqualTo("BEHIND");
        assertThat(relation.get("other").asString()).isEqualTo(target);
        assertThat(relation.get("id").asString()).isNotBlank();
    }

    @Test
    void entity_json_lists_secrets_by_placeholder() throws Exception {
        JsonNode entity = json(http().get("/api/v1/entities/ornek-lb"));

        assertThat(entity.get("secrets").get(0).get("placeholder").asString())
                .isEqualTo("kb:secret/ornek-lb/prod/panel-admin");
    }

    @Test
    void an_unknown_entity_is_404_without_its_id() throws Exception {
        String id = "zz-h6-unknown-" + UUID.randomUUID();

        assertRefused(http().get("/api/v1/entities/" + id), 404, id);
        assertRefused(http().get("/api/v1/entities/" + id, ApiHttp.MARKDOWN), 404, id);
    }

    /** Lead H6 review 4: Accept is read with its q-values; q=0 means not acceptable. */
    @Test
    void accept_is_weighed_by_quality() throws Exception {
        HttpResponse<String> refused =
                http().get("/api/v1/entities/ornek-app-01", "text/markdown;q=0, application/json");
        HttpResponse<String> preferred =
                http().get(
                                "/api/v1/entities/ornek-app-01",
                                "application/json;q=0.5, text/markdown");

        assertThat(refused.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith(ApiHttp.JSON));
        assertThat(preferred.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith(ApiHttp.MARKDOWN));
    }

    private ApiHttp http() {
        return new ApiHttp(port);
    }

    private static JsonNode json(HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }
}
