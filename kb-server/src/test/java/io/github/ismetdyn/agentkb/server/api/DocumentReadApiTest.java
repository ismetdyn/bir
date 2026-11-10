package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/documents} and {@code GET /api/v1/documents/{id}} (docs/CLIENTS.md §1, plan.md
 * H7): the list, newest first, at most {@code limit} (default 50, at most 500) with {@code more}
 * when there were more; one document with its metadata, the absolute path of its blob and its ABOUT
 * targets. A bad limit is 400, an unknown id 404, both {@code {error, hint}}.
 */
class DocumentReadApiTest extends DocumentControllerTestBase {

    private static final String SEED_DOC = "doc:ded48257b6f3";
    private static final String SEED_SHA =
            "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf4";

    @Test
    void one_document_answers_with_its_metadata_absolute_path_and_about() throws Exception {
        HttpResponse<String> reply = get(DocumentHttp.DOCUMENTS + "/" + SEED_DOC);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode body = DocumentHttp.json(reply);
        assertThat(body.get("id").asString()).isEqualTo(SEED_DOC);
        assertThat(body.get("name").asString()).isEqualTo("ornek-proje-calistirma.md");
        assertThat(body.get("sha256").asString()).isEqualTo(SEED_SHA);
        assertThat(body.get("mime").asString()).isEqualTo("text/markdown");
        assertThat(body.get("size").asLong()).isEqualTo(263);
        assertThat(body.get("origin").asString())
                .isEqualTo("wiki.ornek.example/ornek-proje/calistirma");
        assertThat(body.get("addedAt").asString()).isEqualTo("2026-10-30T14:00:00Z");
        assertThat(body.get("path").asString())
                .isEqualTo(
                        repo.resolve(DocumentHttp.blobPath(SEED_SHA)).toAbsolutePath().toString());
        assertThat(Path.of(body.get("path").asString()).isAbsolute()).isTrue();
        assertThat(strings(body.get("about"))).containsExactly("ornek-proje");
    }

    @Test
    void a_new_document_is_listed_first_and_limit_cuts_the_list() throws Exception {
        String sha = documents.upload(uniqueText());
        HttpResponse<String> added = documents.complete(sha, "zz-h7-list.md", ENTITY, null, null);
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);

        // The shared graph holds the documents of every test class: ask for all of them.
        HttpResponse<String> all = get(DocumentHttp.DOCUMENTS + "?limit=500");
        HttpResponse<String> one = get(DocumentHttp.DOCUMENTS + "?limit=1");

        assertThat(all.statusCode()).as(all.body()).isEqualTo(200);
        List<String> ids = ids(DocumentHttp.json(all));
        assertThat(ids).contains(SEED_DOC, DocumentHttp.docId(sha));
        assertThat(DocumentHttp.json(one).get("more").asBoolean()).isTrue();
        assertThat(ids(DocumentHttp.json(one))).containsExactly(ids.getFirst());
        assertThat(ids.indexOf(DocumentHttp.docId(sha))).isLessThan(ids.indexOf(SEED_DOC));
    }

    /**
     * CP4 J3: a Document that came with a snapshot may lack added_at; it is listed after every
     * dated one, not first (Neo4j sorts null highest).
     */
    @Test
    void a_document_without_added_at_is_listed_last() throws Exception {
        String undated = "doc:zzcp4undated";
        store.database()
                .executeTransactionally(
                        "CREATE (:Document {id: $id, name: 'zz-cp4-undated.md'})",
                        Map.of("id", undated));
        try {
            List<String> ids = ids(DocumentHttp.json(get(DocumentHttp.DOCUMENTS + "?limit=500")));

            assertThat(ids).contains(SEED_DOC).endsWith(undated);
        } finally {
            store.database()
                    .executeTransactionally(
                            "MATCH (d:Document {id: $id}) DELETE d", Map.of("id", undated));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "501", "abc", "99999999999999999999"})
    void a_limit_outside_1_to_500_is_refused(String limit) throws Exception {
        HttpResponse<String> reply =
                get(
                        DocumentHttp.DOCUMENTS
                                + "?limit="
                                + URLEncoder.encode(limit, StandardCharsets.UTF_8));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertErrorWithHint(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc:000000000000", "ornek-proje", "doc:zz-h7-not-hex"})
    void an_id_that_names_no_document_is_404(String id) throws Exception {
        HttpResponse<String> reply =
                get(DocumentHttp.DOCUMENTS + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(404);
        assertErrorWithHint(reply);
        assertThat(reply.body()).doesNotContain(id);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return new MaintenanceHttp(port).get(path);
    }

    private static void assertErrorWithHint(HttpResponse<String> reply) {
        JsonNode body = DocumentHttp.json(reply);
        assertThat(body.path("error").asString("")).as(reply.body()).isNotBlank();
        assertThat(body.path("hint").asString("")).as(reply.body()).isNotBlank();
    }

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.get("documents").forEach(document -> ids.add(document.get("id").asString()));
        return ids;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
