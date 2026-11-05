package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The F2 maintenance endpoints as kb calls them (docs/CLIENTS.md §1): {@code POST /api/v1/revert},
 * {@code POST /api/v1/restore} and {@code GET /api/v1/check}, JSON bodies, no token and no Origin
 * (ADR-0020).
 */
public final class MaintenanceHttp {

    public static final String REVERT = "/api/v1/revert";
    public static final String RESTORE = "/api/v1/restore";
    public static final String CHECK = "/api/v1/check";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public MaintenanceHttp(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public HttpResponse<String> post(String path, String json)
            throws IOException, InterruptedException {
        return send(
                request(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET());
    }

    /**
     * Every answer of the three endpoints, for SecretLeakIT: a restore of HEAD (which must
     * succeed), of HEAD by name and of an unknown commit, the check report and the revert of an
     * unknown batch. {@code unknown} is the commit and batch name; a server that echoes request
     * values in errors shows it.
     */
    public Map<String, String> answers(String unknown) throws IOException, InterruptedException {
        Map<String, String> answers = new LinkedHashMap<>();
        HttpResponse<String> restore = post(RESTORE, "{}");
        assertThat(restore.statusCode()).as(restore.body()).isEqualTo(200);
        answers.put("restore", restore.body());
        answers.put("restore at HEAD", post(RESTORE, "{\"at\": \"HEAD\"}").body());
        answers.put(
                "restore at unknown commit", post(RESTORE, "{\"at\": \"" + unknown + "\"}").body());
        answers.put("check", get(CHECK).body());
        answers.put(
                "revert unknown batch", post(REVERT, "{\"batch\": \"" + unknown + "\"}").body());
        return answers;
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
