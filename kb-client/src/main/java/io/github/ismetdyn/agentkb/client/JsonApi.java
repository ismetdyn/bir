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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int SUCCESS_CLASS = 2;
    private static final int STATUS_CLASS = 100;
    private static final Map<String, String> JSON_BODY = Map.of("Content-Type", "application/json");

    private final String base;
    private final String unreachable;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    /** The status, headers and object of a 2xx answer. */
    record Reply(int status, HttpHeaders headers, Map<?, ?> body) {}

    JsonApi(String base) {
        this.base = base;
        this.unreachable =
                "cannot reach the agentkb server at " + base + " (AGENTKB_URL); is it running?";
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
        if (reply.statusCode() / STATUS_CLASS != SUCCESS_CLASS) {
            throw new KbException(
                    "the server answered "
                            + reply.statusCode()
                            + ": "
                            + ServerText.printable(ServerText.error(reply)));
        }
        if (reply.body().isBlank()) {
            return new Reply(reply.statusCode(), reply.headers(), Map.of());
        }
        if (ServerText.printable(Json.parse(reply.body())) instanceof Map<?, ?> answer) {
            return new Reply(reply.statusCode(), reply.headers(), answer);
        }
        throw new KbException("the server answered with JSON that is not an object");
    }

    private HttpResponse<String> exchange(HttpRequest.Builder request) {
        try {
            return http.send(
                    request.timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new KbException(unreachable, Kb.UNREACHABLE);
        } catch (HttpTimeoutException e) {
            throw new KbException("the server at " + base + " did not answer in time");
        } catch (IOException e) {
            throw new KbException(unreachable, Kb.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbException("interrupted while waiting for " + base);
        }
    }

    /** What the server said, made fit to print. */
    private static final class ServerText {

        // Every C0 control but tab and newline, DEL and the C1 range (U+009B is a CSI on many
        // terminals).
        private static final Pattern CONTROL =
                Pattern.compile("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x9f]");

        private ServerText() {}

        /**
         * Server text as kb may print it: strings without terminal control characters, so a record
         * or an error cannot move the cursor, clear the screen or retitle the window.
         */
        static Object printable(Object value) {
            return switch (value) {
                case String text -> CONTROL.matcher(text).replaceAll("");
                case Map<?, ?> map -> {
                    Map<Object, Object> clean = new LinkedHashMap<>();
                    map.forEach((key, item) -> clean.put(key, printable(item)));
                    yield clean;
                }
                case List<?> list -> list.stream().map(ServerText::printable).toList();
                case null, default -> value;
            };
        }

        /** {@code error; hint} of a refusal, else the body as it came. */
        static String error(HttpResponse<String> reply) {
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
}
