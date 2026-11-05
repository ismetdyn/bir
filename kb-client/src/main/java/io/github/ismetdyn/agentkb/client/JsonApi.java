package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON endpoints of the local server kb calls (docs/CLIENTS.md §1: revert, restore, check): no
 * token and no Origin (ADR-0020), the address from {@code AGENTKB_URL} as for kb secret. A refusal
 * ({@code {error, hint}}) and any other non-2xx answer exit 1, an unreachable server exits 3.
 */
final class JsonApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // A restore of 10,000 records takes seconds (RestorePerformanceTest); this only stops a hang.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int SUCCESS_CLASS = 2;
    private static final int STATUS_CLASS = 100;

    private final String base;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    private JsonApi(String base) {
        this.base = base;
    }

    static JsonApi fromEnvironment() {
        return new JsonApi(SecretApi.serverUrl());
    }

    /** {@code text} as a JSON string, so a value with a quote cannot add a field to a body. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < FIRST_PRINTABLE) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** The answer object; {@code get(name)} gives a String, BigDecimal, Boolean, List or Map. */
    Map<?, ?> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    Map<?, ?> post(String path, String json) {
        return send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    private Map<?, ?> send(HttpRequest.Builder request) {
        HttpResponse<String> reply = exchange(request);
        if (reply.statusCode() / STATUS_CLASS != SUCCESS_CLASS) {
            throw new KbException(
                    "the server answered " + reply.statusCode() + ": " + error(reply));
        }
        if (Json.parse(reply.body()) instanceof Map<?, ?> answer) {
            return answer;
        }
        throw new KbException("the server answered with JSON that is not an object");
    }

    private HttpResponse<String> exchange(HttpRequest.Builder request) {
        try {
            return http.send(
                    request.timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw unreachable();
        } catch (HttpTimeoutException e) {
            throw new KbException("the server at " + base + " did not answer in time");
        } catch (IOException e) {
            throw unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbException("interrupted while waiting for " + base);
        }
    }

    private KbException unreachable() {
        return new KbException(
                "cannot reach the agentkb server at " + base + " (AGENTKB_URL); is it running?",
                Kb.UNREACHABLE);
    }

    /** {@code error; hint} of a refusal, else the body as it came. */
    private static String error(HttpResponse<String> reply) {
        try {
            if (Json.parse(reply.body()) instanceof Map<?, ?> body
                    && body.get("error") instanceof String error) {
                return body.get("hint") instanceof String hint ? error + "; " + hint : error;
            }
        } catch (KbException e) {
            // Not JSON (a proxy page, an empty 404): shown as it came.
        }
        return reply.body().strip();
    }
}
