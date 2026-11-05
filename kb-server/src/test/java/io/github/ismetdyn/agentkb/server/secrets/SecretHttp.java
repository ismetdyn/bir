package io.github.ismetdyn.agentkb.server.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The secret endpoint as {@code kb} calls it (docs/CLIENTS.md, ADR-0022 decision 5): {@code PUT}
 * and {@code GET /api/v1/secrets/<id>}, the id with its slashes in the path, the value as a UTF-8
 * {@code text/plain} body, no token (ADR-0020).
 */
public final class SecretHttp {

    public static final String PATH = "/api/v1/secrets/";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public SecretHttp(int port) {
        this.base = "http://127.0.0.1:" + port + PATH;
    }

    public HttpResponse<String> put(String id, String value)
            throws IOException, InterruptedException {
        return send(
                request(id)
                        .header("Content-Type", "text/plain; charset=UTF-8")
                        .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8)));
    }

    public HttpResponse<String> get(String id) throws IOException, InterruptedException {
        return send(request(id).GET());
    }

    /** Any method, content type and body, for the error paths. */
    public HttpResponse<String> send(String method, String id, String contentType, String body)
            throws IOException, InterruptedException {
        return send(
                request(id)
                        .header("Content-Type", contentType)
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    /** A request a web page would send; OriginGuard must refuse it. */
    public HttpResponse<String> getFromBrowser(String id) throws IOException, InterruptedException {
        return send(request(id).header("Origin", "https://evil.example").GET());
    }

    private HttpRequest.Builder request(String id) {
        return HttpRequest.newBuilder(URI.create(base + id)).timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        // The body is decoded as UTF-8 whatever the response says, so a wrong charset shows.
        return http.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
