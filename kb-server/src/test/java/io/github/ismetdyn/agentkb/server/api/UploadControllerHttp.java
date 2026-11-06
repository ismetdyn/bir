package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * The upload staging endpoints as kb doc add calls them (ADR-0025 decision 1, docs/CLIENTS.md §1):
 *
 * <ul>
 *   <li>{@code PUT /api/v1/uploads/{sha}} + {@code Upload-Length: N} → 201 new, 200 existing; both
 *       with {@code Upload-Offset}
 *   <li>{@code PATCH /api/v1/uploads/{sha}} + {@code Upload-Offset: k}, {@code Content-Type:
 *       application/offset+octet-stream}, at most 1 MiB → 204 + the new {@code Upload-Offset}
 *   <li>{@code DELETE /api/v1/uploads/{sha}} → 204
 * </ul>
 *
 * The id is passed as raw path text, so tests can send ids that are not a sha256.
 */
public final class UploadControllerHttp {

    public static final String UPLOADS = "/api/v1/uploads/";
    public static final String OFFSET_STREAM = "application/offset+octet-stream";
    public static final String UPLOAD_LENGTH = "Upload-Length";
    public static final String UPLOAD_OFFSET = "Upload-Offset";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int SHA_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public UploadControllerHttp(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    /** A fresh id of the right shape; staging does not compare it with the content (H4 does). */
    public static String randomSha() {
        byte[] bytes = new byte[SHA_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** {@code Upload-Offset} of the answer; fails the test when the header is missing. */
    public static long offset(HttpResponse<?> reply) {
        String value = reply.headers().firstValue(UPLOAD_OFFSET).orElse(null);
        assertThat(value).as("Upload-Offset of a %d answer", reply.statusCode()).isNotNull();
        return Long.parseLong(value);
    }

    public HttpResponse<String> put(String id, long length)
            throws IOException, InterruptedException {
        return put(id, Long.toString(length));
    }

    /** {@code length} null sends no Upload-Length header. */
    public HttpResponse<String> put(String id, @Nullable String length)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = request(id).PUT(BodyPublishers.noBody());
        if (length != null) {
            request.header(UPLOAD_LENGTH, length);
        }
        return send(request);
    }

    public HttpResponse<String> patch(String id, long offset, byte[] body)
            throws IOException, InterruptedException {
        return patch(id, offset, OFFSET_STREAM, BodyPublishers.ofByteArray(body));
    }

    /** A PATCH with any Content-Type. */
    public HttpResponse<String> patch(
            String id, long offset, String contentType, BodyPublisher body)
            throws IOException, InterruptedException {
        return send(
                request(id)
                        .header(UPLOAD_OFFSET, Long.toString(offset))
                        .header("Content-Type", contentType)
                        .method("PATCH", body));
    }

    public HttpResponse<String> delete(String id) throws IOException, InterruptedException {
        return send(request(id).DELETE());
    }

    /** Whether {@code git lfs} runs on this machine's PATH. */
    public static boolean gitLfsPresent() throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder("git", "lfs", "version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        return process.waitFor() == 0;
    }

    private HttpRequest.Builder request(String id) {
        return HttpRequest.newBuilder(URI.create(base + UPLOADS + id)).timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return http.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
