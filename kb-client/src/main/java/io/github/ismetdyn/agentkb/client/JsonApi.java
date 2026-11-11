package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JSON endpoints of the local server kb calls (docs/CLIENTS.md §1: revert, restore, check): no
 * token and no Origin (ADR-0020), the address from {@code AGENTKB_URL} as for kb secret. A refusal
 * ({@code {error, hint}}) and any other non-2xx answer exit 1, an unreachable server exits 3. All
 * text of an answer or error comes back without terminal control characters (tab and newline stay).
 * A 2xx answer without a body is an empty object. {@link #request} sends raw bytes with its own
 * headers, for the resumable upload (ADR-0025 decision 1).
 */
final class JsonApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // A restore of 10,000 records takes seconds (RestorePerformanceTest); this only stops a hang.
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final Map<String, String> JSON_BODY = Map.of("Content-Type", "application/json");

    private final String base;
    private final Duration timeout;
    // What a broken connection is: the lost message if there is one, else an unreachable server.
    private final String broken;
    // What no answer in time is: the lost message (exit 3) if there is one, else a failure.
    private final String late;
    private final int lateExitCode;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    /** The status, headers and object of a 2xx answer. */
    record Reply(int status, HttpHeaders headers, Map<?, ?> body) {}

    JsonApi(String base) {
        this(base, REQUEST_TIMEOUT, null);
    }

    /**
     * Requests that wait up to {@code timeout}. With {@code lost} set (the parts and completion of
     * an upload whose PUT answered, H7 review), a broken connection or a server that does not
     * answer in time is that message with exit 3, because the same command resumes the upload.
     */
    JsonApi(String base, Duration timeout, @Nullable String lost) {
        this.base = base;
        this.timeout = timeout;
        this.late = lost != null ? lost : "the server at " + base + " did not answer in time";
        this.lateExitCode = lost != null ? Kb.UNREACHABLE : Kb.FAILED;
        this.broken =
                lost != null
                        ? lost
                        : "cannot reach the agentkb server at "
                                + base
                                + " (AGENTKB_URL); is it running?";
    }

    static JsonApi fromEnvironment() {
        return new JsonApi(SecretApi.serverUrl());
    }

    /**
     * The body of {@code GET path} as the server wrote it for {@code accept} (text/markdown, or
     * application/json for kb show --json; ADR-0025 decision 13), without terminal control
     * characters; errors as for JSON.
     */
    String text(String path, String accept) {
        HttpResponse<String> reply =
                exchange(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .header("Accept", accept)
                                .GET());
        return (String) ServerText.printable(reply.body());
    }

    /** The answer object; {@code get(name)} gives a String, BigDecimal, Boolean, List or Map. */
    Map<?, ?> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET()).body();
    }

    Map<?, ?> post(String path, String json) {
        return request("POST", path, JSON_BODY, json.getBytes(StandardCharsets.UTF_8)).body();
    }

    Map<?, ?> put(String path, String json) {
        return request("PUT", path, JSON_BODY, json.getBytes(StandardCharsets.UTF_8)).body();
    }

    Map<?, ?> patch(String path, String json) {
        return request("PATCH", path, JSON_BODY, json.getBytes(StandardCharsets.UTF_8)).body();
    }

    Map<?, ?> delete(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE()).body();
    }

    /** {@code body} as it is, with {@code headers} (e.g. Upload-Offset); the 2xx status stays. */
    Reply request(String method, String path, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return send(request);
    }

    private Reply send(HttpRequest.Builder request) {
        HttpResponse<String> reply = exchange(request);
        if (reply.body().isBlank()) {
            return new Reply(reply.statusCode(), reply.headers(), Map.of());
        }
        if (ServerText.printable(Json.parse(reply.body())) instanceof Map<?, ?> answer) {
            return new Reply(reply.statusCode(), reply.headers(), answer);
        }
        throw new KbException("the server answered with JSON that is not an object");
    }

    /** The 2xx reply; any other status is the server's error and hint as exit 1. */
    private HttpResponse<String> exchange(HttpRequest.Builder request) {
        try {
            return ServerText.success(
                    http.send(
                            request.timeout(timeout).build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (HttpConnectTimeoutException e) {
            throw new KbException(broken, Kb.UNREACHABLE);
        } catch (HttpTimeoutException e) {
            throw new KbException(late, lateExitCode);
        } catch (IOException e) {
            throw new KbException(broken, Kb.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbException("interrupted while waiting for " + base);
        }
    }
}
