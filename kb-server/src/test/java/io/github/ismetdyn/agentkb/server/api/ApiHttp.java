package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The H6 endpoints as kb and the TUI call them (docs/CLIENTS.md §1): JSON in and out, no token and
 * no Origin (ADR-0020); {@code GET /entities/{id}} also as Markdown.
 */
public final class ApiHttp {

    public static final String JSON = "application/json";
    public static final String MARKDOWN = "text/markdown";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public ApiHttp(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    /**
     * A refusal: {@code status} and {@code {error, hint}} only, holding none of {@code notEchoed}
     * (invariant 10: a refusal never repeats a request value).
     */
    public static void assertRefused(HttpResponse<String> reply, int status, String... notEchoed) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(status);
        JsonNode body = JsonMapper.shared().readTree(reply.body());
        assertThat(Set.copyOf(body.propertyNames()))
                .as(reply.body())
                .containsExactlyInAnyOrder("error", "hint");
        for (String value : notEchoed) {
            assertThat(reply.body()).doesNotContain(value);
        }
    }

    /** A query parameter value, URL-encoded. */
    public static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, JSON);
    }

    public HttpResponse<String> get(String path, String accept)
            throws IOException, InterruptedException {
        return send(request(path).header("Accept", accept).GET());
    }

    public HttpResponse<String> post(String path, String json)
            throws IOException, InterruptedException {
        return send("POST", path, json);
    }

    public HttpResponse<String> patch(String path, String json)
            throws IOException, InterruptedException {
        return send("PATCH", path, json);
    }

    private HttpResponse<String> send(String method, String path, String json)
            throws IOException, InterruptedException {
        return send(
                request(path)
                        .header("Content-Type", JSON)
                        .header("Accept", JSON)
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        return http.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
