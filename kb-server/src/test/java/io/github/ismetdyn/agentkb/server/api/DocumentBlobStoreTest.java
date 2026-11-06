package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What already stands under {@code blobs/} may come from a {@code kb sync} pull, so it is not
 * trusted (CP2 F1, F4, J2): a link is never followed when a blob is written (CWE-59), a stored blob
 * counts only if its bytes hash to its name, and the staging of content already stored is removed.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentBlobStoreTest extends DocumentControllerTestBase {

    private static final String NAME = "zz-h4-store.md";
    private static final String VICTIM = "the user's own file\n";
    private static final int TRIES = 10_000;

    @TempDir Path outside;

    @Test
    void a_planted_tmp_link_is_not_followed(@TempDir Path elsewhere) throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);
        Path victim = Files.writeString(elsewhere.resolve("victim"), VICTIM);
        Path tmp = Files.createDirectories(blob(sha).getParent()).resolve(sha + ".tmp");
        Files.createSymbolicLink(tmp, victim);

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(Files.readString(victim)).as("the link target is untouched").isEqualTo(VICTIM);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(Files.isRegularFile(blob(sha), LinkOption.NOFOLLOW_LINKS))
                .as("the blob is a file of its own, no link")
                .isTrue();
        assertThat(Files.readAllBytes(blob(sha))).isEqualTo(content);
        assertThat(blobFiles(sha)).as("no .tmp left").containsExactly(blob(sha));
    }

    @Test
    void a_linked_blob_directory_is_refused_and_nothing_is_written_through_it() throws Exception {
        String sha = shaWithoutBlobDirectory();
        Path dir = blob(sha).getParent();
        Files.createDirectories(dir.getParent());
        Files.createSymbolicLink(dir, outside);
        try {
            HttpResponse<String> reply = documents.complete(sha, NAME);

            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(500);
            assertNoLeak(reply.body());
            try (Stream<Path> written = Files.list(outside)) {
                assertThat(written).as("nothing written through the link").isEmpty();
            }
            assertThat(documentNodes(sha)).isEmpty();
        } finally {
            Files.delete(dir);
        }
    }

    @Test
    void a_stored_blob_that_does_not_hash_to_its_name_is_replaced_by_the_upload(
            CapturedOutput output) throws Exception {
        byte[] content = uniqueText();
        String sha = DocumentHttp.sha256(content);
        byte[] tampered = new byte[content.length];
        Files.write(Files.createDirectories(blob(sha).getParent()).resolve(sha), tampered);

        HttpResponse<String> put = uploads.put(sha, content.length);

        assertThat(put.statusCode()).as("not EXISTING: %s", put.body()).isEqualTo(201);
        documents.uploadAs(sha, content);
        HttpResponse<String> reply = documents.complete(sha, NAME);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(Files.readAllBytes(blob(sha))).as("replaced by the upload").isEqualTo(content);
        assertThat(blobFiles(sha)).containsExactly(blob(sha));
        assertThat(output.getAll().lines().filter(line -> line.contains("event=blob_mismatch")))
                .as("a WARN with the hash prefix only")
                .isNotEmpty()
                .allMatch(line -> line.contains("WARN"))
                .allMatch(line -> line.contains(DocumentHttp.docId(sha)))
                .noneMatch(line -> line.contains(sha));
    }

    @Test
    void a_stored_blob_that_does_not_hash_to_its_name_is_no_document() throws Exception {
        byte[] content = uniqueText();
        String sha = DocumentHttp.sha256(content);
        Files.write(
                Files.createDirectories(blob(sha).getParent()).resolve(sha),
                "other bytes\n".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(404);
        assertThat(documentNodes(sha)).isEmpty();
    }

    /** A blob that arrived by sync while the same file was uploaded: the upload is not kept. */
    @Test
    void completing_content_already_stored_removes_its_staging() throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);
        Files.write(Files.createDirectories(blob(sha).getParent()).resolve(sha), content);

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(stagedFiles(sha)).as("staging removed").isEmpty();
    }

    /** A sha256 whose {@code blobs/xx} does not exist yet, so the test owns that directory. */
    private String shaWithoutBlobDirectory() throws Exception {
        for (int i = 0; i < TRIES; i++) {
            byte[] content = uniqueText();
            String sha = DocumentHttp.sha256(content);
            if (!Files.exists(blob(sha).getParent(), LinkOption.NOFOLLOW_LINKS)) {
                documents.upload(content);
                return sha;
            }
        }
        throw new IllegalStateException("every blobs/xx directory exists");
    }
}
