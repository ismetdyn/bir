package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code kb doc add} as the client runs it (ADR-0025 decision 1, docs/CLIENTS.md §1): the upload
 * through {@link UploadControllerHttp} in parts of at most 1 MiB, then {@code POST
 * /api/v1/documents {sha256, name, about?, origin?, replaces?}} → 201 {@code {id, path}} for a new
 * document, 200 with the existing one for the same content.
 */
public final class DocumentHttp {

    public static final String DOCUMENTS = "/api/v1/documents";
    public static final int PART_BYTES = 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;
    private final UploadControllerHttp uploads;

    public DocumentHttp(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.uploads = new UploadControllerHttp(port);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code doc:} and the first 12 hex digits of the sha256 (ARCHITECTURE §4). */
    public static String docId(String sha) {
        return "doc:" + sha.substring(0, 12);
    }

    /** The blob's place in the knowledge repo, {@code blobs/<first 2>/<sha>} (SPEC §6.7). */
    public static String blobPath(String sha) {
        return "blobs/" + sha.substring(0, 2) + "/" + sha;
    }

    /** Uploads {@code content} completely under its own sha256; returns that sha256. */
    public String upload(byte[] content) throws IOException, InterruptedException {
        String sha = sha256(content);
        uploadAs(sha, content);
        return sha;
    }

    /** Uploads {@code content} completely under {@code sha}, which need not be its hash. */
    public void uploadAs(String sha, byte[] content) throws IOException, InterruptedException {
        HttpResponse<String> put = uploads.put(sha, content.length);
        assertThat(put.statusCode()).as("PUT: %s", put.body()).isIn(200, 201);
        for (long offset = UploadControllerHttp.offset(put); offset < content.length; ) {
            int end = (int) Math.min(content.length, offset + PART_BYTES);
            HttpResponse<String> patch =
                    uploads.patch(sha, offset, Arrays.copyOfRange(content, (int) offset, end));
            assertThat(patch.statusCode())
                    .as("PATCH at %d: %s", offset, patch.body())
                    .isEqualTo(204);
            offset = UploadControllerHttp.offset(patch);
        }
    }

    /** POST /documents with the given fields; a null field is left out of the body. */
    public HttpResponse<String> complete(
            String sha,
            @Nullable String name,
            @Nullable String about,
            @Nullable String origin,
            @Nullable String replaces)
            throws IOException, InterruptedException {
        Map<String, @Nullable String> fields = new LinkedHashMap<>();
        fields.put("sha256", sha);
        fields.put("name", name);
        fields.put("about", about);
        fields.put("origin", origin);
        fields.put("replaces", replaces);
        fields.values().removeIf(Objects::isNull);
        return post(JSON.writeValueAsString(fields));
    }

    public HttpResponse<String> complete(String sha, String name)
            throws IOException, InterruptedException {
        return complete(sha, name, null, null, null);
    }

    /** {@link #complete(String, String)} on another thread. */
    public CompletableFuture<HttpResponse<String>> completeAsync(String sha, String name) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return complete(sha, name);
                    } catch (IOException | InterruptedException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    /** POST /documents with a raw body. */
    public HttpResponse<String> post(String json) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create(base + DOCUMENTS))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static JsonNode json(HttpResponse<String> reply) {
        return JSON.readTree(reply.body());
    }
}
