package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.DocumentHttp.blobPath;
import static io.github.ismetdyn.agentkb.server.api.DocumentHttp.docId;
import static io.github.ismetdyn.agentkb.server.api.DocumentHttp.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A completed upload becomes a blob and a Document node (SPEC §6.7, ADR-0025 decisions 2 and 9,
 * lead decisions "aynı içerik" and "--replaces"): 201 {@code {id, path}}; the same content again is
 * 200 with the existing document and only adds a new ABOUT; {@code replaces} links the new document
 * to the old with SUPERSEDES; {@code kb revert --batch} deletes the node and keeps the blob, which
 * can then be added again without a new upload.
 */
class DocumentControllerAddTest extends DocumentControllerTestBase {

    private static final String NAME = "zz-h4-runbook.md";
    private static final String ORIGIN = "wiki.ornek.example/zz-h4/runbook";
    private static final String ABOUT = "ABOUT";

    @Test
    void a_completed_upload_is_stored_as_blob_and_document_node() throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);

        HttpResponse<String> reply = documents.complete(sha, NAME, ENTITY, ORIGIN, null);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        JsonNode answer = json(reply);
        assertThat(answer.path("id").asString()).isEqualTo(docId(sha));
        assertThat(answer.path("path").asString()).endsWith(blobPath(sha));
        assertThat(Files.readAllBytes(blob(sha))).as("blob bytes").isEqualTo(content);
        assertThat(blobFiles(sha)).as("no .tmp left beside the blob").containsExactly(blob(sha));
        assertThat(stagedFiles(sha)).as("staging removed after completion").isEmpty();
        assertThat(documentNodes(sha)).hasSize(1);
        Map<String, Object> node = documentNodes(sha).getFirst();
        assertThat(node)
                .containsEntry("id", docId(sha))
                .containsEntry("sha256", sha)
                .containsEntry("name", NAME)
                .containsEntry("origin", ORIGIN)
                .containsEntry("path", blobPath(sha))
                .containsEntry("source", "user")
                .containsEntry("mime", "text/markdown")
                .containsKey("added_at")
                .containsKey("batch");
        assertThat(((Number) node.get("size")).longValue()).isEqualTo(content.length);
        assertThat(targets(docId(sha), ABOUT)).containsExactly(ENTITY);
    }

    /** SPEC §6.2: mime is guessed from the name; an unknown kind is a plain byte stream. */
    @Test
    void a_name_of_no_known_type_gets_the_octet_stream_mime() throws Exception {
        String sha = documents.upload(uniqueText());

        HttpResponse<String> reply = documents.complete(sha, "zz-h4-notes");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(documentNodes(sha).getFirst()).containsEntry("mime", "application/octet-stream");
    }

    @Test
    void the_same_content_again_answers_200_with_the_existing_document() throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);
        assertThat(documents.complete(sha, NAME).statusCode()).isEqualTo(201);

        // The client's next run: PUT finds the blob complete, so it goes straight to POST.
        HttpResponse<String> put = uploads.put(sha, content.length);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(UploadControllerHttp.offset(put)).isEqualTo(content.length);
        HttpResponse<String> again = documents.complete(sha, "zz-h4-another-name.md");

        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(json(again).path("id").asString()).isEqualTo(docId(sha));
        assertThat(documentNodes(sha)).as("still one node").hasSize(1);
        assertThat(documentNodes(sha).getFirst()).containsEntry("name", NAME);
        assertThat(Files.readAllBytes(blob(sha))).isEqualTo(content);
    }

    @Test
    void a_repeated_add_with_a_new_about_adds_one_more_about_edge() throws Exception {
        String sha = documents.upload(uniqueText());
        assertThat(documents.complete(sha, NAME, ENTITY, null, null).statusCode()).isEqualTo(201);

        HttpResponse<String> other = documents.complete(sha, NAME, OTHER_ENTITY, null, null);
        HttpResponse<String> repeated = documents.complete(sha, NAME, ENTITY, null, null);

        assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(200);
        assertThat(targets(docId(sha), ABOUT))
                .as("one ABOUT per target, the repeated one not doubled")
                .containsExactly(OTHER_ENTITY, ENTITY);
        assertThat(documentNodes(sha)).hasSize(1);
    }

    @Test
    void replaces_links_the_new_document_to_the_old_with_supersedes() throws Exception {
        String oldSha = documents.upload(uniqueText());
        assertThat(documents.complete(oldSha, NAME).statusCode()).isEqualTo(201);
        String newSha = documents.upload(uniqueText());

        HttpResponse<String> reply = documents.complete(newSha, NAME, null, null, docId(oldSha));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(targets(docId(newSha), "SUPERSEDES")).containsExactly(docId(oldSha));
        assertThat(documentNodes(oldSha)).as("the old document stays (no deletion)").hasSize(1);
        assertThat(Files.exists(blob(oldSha))).isTrue();
    }

    @Test
    void revert_deletes_the_node_keeps_the_blob_and_the_blob_can_be_added_again() throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);
        assertThat(documents.complete(sha, NAME, ENTITY, null, null).statusCode()).isEqualTo(201);
        String batch = (String) documentNodes(sha).getFirst().get("batch");

        HttpResponse<String> revert =
                new MaintenanceHttp(port)
                        .post(MaintenanceHttp.REVERT, "{\"batch\": \"" + batch + "\"}");

        assertThat(revert.statusCode()).as(revert.body()).isEqualTo(200);
        assertThat(documentNodes(sha)).as("node deleted by revert").isEmpty();
        assertThat(targets(docId(sha), ABOUT)).isEmpty();
        assertThat(Files.readAllBytes(blob(sha))).as("blob never deleted").isEqualTo(content);

        HttpResponse<String> put = uploads.put(sha, content.length);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        HttpResponse<String> again = documents.complete(sha, NAME);
        assertThat(again.statusCode()).as("from the kept blob: %s", again.body()).isEqualTo(201);
        assertThat(documentNodes(sha)).hasSize(1);
    }
}
