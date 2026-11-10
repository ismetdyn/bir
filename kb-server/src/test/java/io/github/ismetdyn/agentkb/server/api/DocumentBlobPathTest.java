package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * CP4 S1 (CWE-59): blobs/ also fills from a kb sync pull, and kb_read tells the agent it may open
 * the path. So the path is handed out, by kb_read and {@code GET /api/v1/documents/{id}}, only for
 * a regular file in real directories; a link in the blob's place, or no blob at all, gives no path.
 * Each test adds a document of its own and changes only its blob.
 */
class DocumentBlobPathTest extends DocumentControllerTestBase {

    @Test
    void an_intact_blob_is_handed_out_as_an_absolute_path() throws Exception {
        String sha = added("zz-cp4-intact.md");

        assertThat(path(sha).asString()).isEqualTo(blob(sha).toAbsolutePath().toString());
        assertThat(read(sha)).contains("Dosya: " + blob(sha).toAbsolutePath());
    }

    @Test
    void a_link_in_place_of_the_blob_gives_no_path() throws Exception {
        String sha = added("zz-cp4-link.md");
        Path target = Files.createTempFile(staging().getParent(), "zz-cp4-target", ".md");
        Files.delete(blob(sha));
        Files.createSymbolicLink(blob(sha), target);

        assertThat(path(sha).isNull()).as("path is null").isTrue();
        assertThat(read(sha)).doesNotContain("Dosya:").doesNotContain(target.toString());
    }

    @Test
    void a_missing_blob_gives_no_path() throws Exception {
        String sha = added("zz-cp4-missing.md");
        Files.delete(blob(sha));

        assertThat(path(sha).isNull()).as("path is null").isTrue();
        assertThat(read(sha)).doesNotContain("Dosya:");
    }

    private String added(String name) throws Exception {
        String sha = documents.upload(uniqueText());
        HttpResponse<String> reply = documents.complete(sha, name);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        return sha;
    }

    private JsonNode path(String sha) throws Exception {
        HttpResponse<String> reply =
                new MaintenanceHttp(port)
                        .get(DocumentHttp.DOCUMENTS + "/" + DocumentHttp.docId(sha));
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return DocumentHttp.json(reply).get("path");
    }

    private String read(String sha) throws Exception {
        return McpClient.open(port).text("kb_read", Map.of("id", DocumentHttp.docId(sha)));
    }
}
