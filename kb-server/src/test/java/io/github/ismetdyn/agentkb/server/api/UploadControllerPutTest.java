package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.offset;
import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.randomSha;
import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code PUT /api/v1/uploads/{sha}} + {@code Upload-Length: N} (UploadControllerTestBase). */
class UploadControllerPutTest extends UploadControllerTestBase {

    // Above 20 MB read as either 20,000,000 or 20 MiB; the exact boundary is not specified.
    private static final long OVER_LFS_THRESHOLD = 25L * MIB;
    private static final long UNDER_LFS_THRESHOLD = 19_000_000L;
    private static final int BLOB_LENGTH = 12;

    @Test
    void new_upload_is_created_at_offset_zero() throws Exception {
        HttpResponse<String> reply = uploads.put(randomSha(), STAGED_LENGTH);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(offset(reply)).isZero();
    }

    @Test
    void existing_upload_answers_200_with_the_bytes_received_so_far() throws Exception {
        String sha = randomSha();
        stagePart(uploads, sha);

        HttpResponse<String> reply = uploads.put(sha, STAGED_LENGTH);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(offset(reply)).isEqualTo(STAGED_BYTES);
    }

    /** A content already in blobs/ needs no bytes: the client goes straight to POST /documents. */
    @Test
    void content_already_in_the_blob_store_is_complete() throws Exception {
        String sha = randomSha();
        Path blob = repo.resolve("blobs").resolve(sha.substring(0, 2)).resolve(sha);
        Files.createDirectories(blob.getParent());
        Files.write(blob, new byte[BLOB_LENGTH]);
        try {
            HttpResponse<String> reply = uploads.put(sha, BLOB_LENGTH);

            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
            assertThat(offset(reply)).isEqualTo(BLOB_LENGTH);
        } finally {
            Files.delete(blob);
        }
    }

    @Test
    void same_sha_with_another_length_is_a_conflict() throws Exception {
        String sha = randomSha();
        uploads.put(sha, STAGED_LENGTH);

        HttpResponse<String> reply = uploads.put(sha, STAGED_LENGTH + 1);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(409);
        assertNoLeak(reply.body());
        HttpResponse<String> again = uploads.put(sha, STAGED_LENGTH);
        assertThat(again.statusCode()).as("the first length still holds").isEqualTo(200);
    }

    // Not " 10": Tomcat strips whitespace around a field value (RFC 9110 5.5), so the server never
    // sees it.
    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-1", "1.5", "0x10", "99999999999999999999", "1 0", "+10"})
    void malformed_upload_length_is_refused(String length) throws Exception {
        HttpResponse<String> reply = uploads.put(randomSha(), length);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply.body());
    }

    @Test
    void missing_upload_length_is_refused() throws Exception {
        HttpResponse<String> reply = uploads.put(randomSha(), (String) null);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply.body());
    }

    /**
     * Requires the space check before the git-lfs rule: a file that does not fit cannot be stored
     * with or without git-lfs, and otherwise 507 could never be seen on a machine without git-lfs.
     */
    @Test
    void length_over_the_usable_space_is_refused() throws Exception {
        Files.createDirectories(dataDir);
        long usable = Files.getFileStore(dataDir).getUsableSpace();

        HttpResponse<String> reply = uploads.put(randomSha(), usable + MIB);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(507);
        assertNoLeak(reply.body());
    }

    /** Runs only where git-lfs is missing; elsewhere it is reported as not run, with the reason. */
    @Test
    void file_over_20_mb_without_git_lfs_is_refused_at_put() throws Exception {
        Assumptions.assumeFalse(
                UploadControllerHttp.gitLfsPresent(),
                "not run: git-lfs present, this case needs a machine without it");

        HttpResponse<String> reply = uploads.put(randomSha(), OVER_LFS_THRESHOLD);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(422);
        assertNoLeak(reply.body());
    }

    @Test
    void file_under_20_mb_needs_no_git_lfs() throws Exception {
        HttpResponse<String> reply = uploads.put(randomSha(), UNDER_LFS_THRESHOLD);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
    }
}
