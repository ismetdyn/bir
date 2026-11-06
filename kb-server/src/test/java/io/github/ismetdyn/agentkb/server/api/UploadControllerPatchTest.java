package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.offset;
import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.randomSha;
import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code PATCH /api/v1/uploads/{sha}} + {@code Upload-Offset: k}, {@code Content-Type:
 * application/offset+octet-stream}, at most 1 MiB → 204 + the new {@code Upload-Offset}
 * (UploadControllerTestBase).
 */
class UploadControllerPatchTest extends UploadControllerTestBase {

    private static final long HELD_PATCH_WAIT_SECONDS = 30;
    private static final long POLL_MILLIS = 20;
    private static final String CRLF = "\r\n";
    private static final Pattern OFFSET_HEADER =
            Pattern.compile("(?im)^" + UploadControllerHttp.UPLOAD_OFFSET + ":\\s*(\\d+)");
    private static final int HALF = 512;

    @Test
    void patches_append_and_report_the_new_offset() throws Exception {
        String sha = randomSha();
        uploads.put(sha, STAGED_LENGTH);

        HttpResponse<String> first = uploads.patch(sha, 0, new byte[6]);
        HttpResponse<String> second = uploads.patch(sha, 6, new byte[4]);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(204);
        assertThat(offset(first)).isEqualTo(6);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(204);
        assertThat(offset(second)).isEqualTo(STAGED_LENGTH);
        assertThat(offset(uploads.put(sha, STAGED_LENGTH))).isEqualTo(STAGED_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, STAGED_BYTES - 1, STAGED_BYTES + 1})
    void patch_at_the_wrong_offset_is_a_conflict_that_names_the_right_one(long wrong)
            throws Exception {
        String sha = randomSha();
        stagePart(uploads, sha);

        HttpResponse<String> reply = uploads.patch(sha, wrong, new byte[1]);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(409);
        assertThat(offset(reply)).isEqualTo(STAGED_BYTES);
        assertNoLeak(reply.body());
        assertThat(offset(uploads.put(sha, STAGED_LENGTH)))
                .as("nothing written")
                .isEqualTo(STAGED_BYTES);
    }

    @Test
    void patch_past_the_declared_length_is_refused() throws Exception {
        String sha = randomSha();
        stagePart(uploads, sha);

        HttpResponse<String> reply =
                uploads.patch(sha, STAGED_BYTES, new byte[STAGED_LENGTH - STAGED_BYTES + 1]);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(413);
        assertNoLeak(reply.body());
        assertThat(offset(uploads.put(sha, STAGED_LENGTH)))
                .as("nothing written")
                .isEqualTo(STAGED_BYTES);
    }

    @Test
    void patch_of_exactly_one_mib_is_accepted() throws Exception {
        String sha = randomSha();
        uploads.put(sha, 2L * MIB);

        HttpResponse<String> reply = uploads.patch(sha, 0, new byte[MIB]);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(204);
        assertThat(offset(reply)).isEqualTo(MIB);
    }

    /** The 413 itself held before H3: RequestBodyLimitFilter refuses every body over 1 MiB. */
    @Test
    void patch_over_one_mib_is_refused() throws Exception {
        String sha = randomSha();
        uploads.put(sha, 2L * MIB);

        HttpResponse<String> reply = uploads.patch(sha, 0, new byte[MIB + 1]);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(413);
        assertThat(offset(uploads.put(sha, 2L * MIB))).as("nothing written").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/octet-stream", "application/json", "text/plain"})
    void patch_with_another_content_type_is_refused(String contentType) throws Exception {
        String sha = randomSha();
        uploads.put(sha, STAGED_LENGTH);

        HttpResponse<String> reply =
                uploads.patch(
                        sha, 0, contentType, BodyPublishers.ofByteArray(new byte[STAGED_BYTES]));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(415);
        assertNoLeak(reply.body());
        assertThat(offset(uploads.put(sha, STAGED_LENGTH))).as("nothing written").isZero();
    }

    /**
     * Two PATCHes of one upload in the same JVM: while the first is still receiving its body, a
     * second one at the old offset and one at the offset the first has reached are both refused. A
     * FileChannel lock taken twice in one JVM throws OverlappingFileLockException instead of
     * returning null; that too must end in 409, not 500.
     *
     * <p>The first PATCH goes over a plain socket: java.net.http.HttpClient does not send the
     * headers while its body publisher is held, so the server would never see it. Its first half is
     * on the server once PUT reports it as received.
     */
    @Test
    void concurrent_patch_of_the_same_upload_is_a_conflict() throws Exception {
        String sha = randomSha();
        uploads.put(sha, 4L * HALF);
        try (Socket held = new Socket()) {
            held.setSoTimeout((int) TimeUnit.SECONDS.toMillis(HELD_PATCH_WAIT_SECONDS));
            held.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            OutputStream out = held.getOutputStream();
            out.write(
                    ("PATCH "
                                    + UploadControllerHttp.UPLOADS
                                    + sha
                                    + " HTTP/1.1"
                                    + CRLF
                                    + "Host: 127.0.0.1:"
                                    + port
                                    + CRLF
                                    + UploadControllerHttp.UPLOAD_OFFSET
                                    + ": 0"
                                    + CRLF
                                    + "Content-Type: "
                                    + UploadControllerHttp.OFFSET_STREAM
                                    + CRLF
                                    + "Content-Length: "
                                    + 2 * HALF
                                    + CRLF
                                    + "Connection: close"
                                    + CRLF
                                    + CRLF)
                            .getBytes(StandardCharsets.ISO_8859_1));
            out.write(new byte[HALF]);
            out.flush();
            awaitReceived(sha, HALF);

            HttpResponse<String> atStart = uploads.patch(sha, 0, new byte[HALF]);
            HttpResponse<String> atHalf = uploads.patch(sha, HALF, new byte[HALF]);
            out.write(new byte[HALF]);
            out.flush();
            String firstReply =
                    new String(held.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);

            assertThat(atStart.statusCode()).as(atStart.body()).isEqualTo(409);
            assertThat(atHalf.statusCode()).as(atHalf.body()).isEqualTo(409);
            assertNoLeak(atStart.body());
            assertNoLeak(atHalf.body());
            assertThat(firstReply).startsWith("HTTP/1.1 204");
            Matcher firstOffset = OFFSET_HEADER.matcher(firstReply);
            assertThat(firstOffset.find()).as(firstReply).isTrue();
            assertThat(Long.parseLong(firstOffset.group(1))).isEqualTo(2L * HALF);
        }
        assertThat(offset(uploads.put(sha, 4L * HALF)))
                .as("only the first PATCH wrote")
                .isEqualTo(2L * HALF);
    }

    /** Waits until PUT reports {@code bytes} received for {@code sha}. */
    private void awaitReceived(String sha, long bytes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HELD_PATCH_WAIT_SECONDS);
        long received;
        while ((received = offset(uploads.put(sha, 4L * HALF))) != bytes
                && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
        }
        assertThat(received).as("bytes of the held PATCH on the server").isEqualTo(bytes);
    }
}
