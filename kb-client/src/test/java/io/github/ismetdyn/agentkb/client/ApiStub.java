package io.github.ismetdyn.agentkb.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A loopback server that answers {@code <METHOD> <path>} with a fixed status, JSON body and extra
 * headers, and records every request with its body as text and as bytes, so a test sees exactly
 * what {@code kb} sent and how it treats the answer. An unknown method and path answers 404. Public
 * for the kb tui tests in client.tui.
 */
public final class ApiStub implements AutoCloseable {

    private static final int NOT_FOUND = 404;
    private static final int NO_BODY = -1;

    /**
     * {@code body} is {@code bytes} read as UTF-8; {@code bytes} is what arrived; {@code rawQuery}
     * is the query as sent, empty if none.
     */
    public record Request(
            String method,
            String rawPath,
            String rawQuery,
            Map<String, List<String>> headers,
            String body,
            byte[] bytes) {}

    private record Answer(int status, String json, Map<String, String> headers) {}

    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public ApiStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public ApiStub answer(String method, String path, int status, String json) {
        return answer(method, path, status, json, Map.of());
    }

    /** As above with response {@code headers}, e.g. {@code Upload-Offset}. */
    ApiStub answer(
            String method, String path, int status, String json, Map<String, String> headers) {
        answers.put(method + " " + path, new Answer(status, json, Map.copyOf(headers)));
        return this;
    }

    /**
     * Holds the answer to {@code <METHOD> <path>} back for {@code delay}; the request is recorded
     * first.
     */
    public ApiStub delay(String method, String path, Duration delay) {
        delays.put(method + " " + path, delay);
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Runs kb against this stub, stdin empty. */
    KbProcess.Result kb(Path workDir, String... args) {
        return KbProcess.run(url(), "", workDir, args);
    }

    /** {@code "name": "value"} in a JSON body, any spacing. */
    static Pattern jsonField(String name, String value) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"" + Pattern.quote(value) + "\"");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] received = exchange.getRequestBody().readAllBytes();
            String body = new String(received, StandardCharsets.UTF_8);
            String rawPath = exchange.getRequestURI().getRawPath();
            requests.add(
                    new Request(
                            exchange.getRequestMethod(),
                            rawPath,
                            Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""),
                            Map.copyOf(exchange.getRequestHeaders()),
                            body,
                            received));
            String key = exchange.getRequestMethod() + " " + rawPath;
            Duration delay = delays.get(key);
            if (delay != null) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer answer = answers.get(key);
            if (answer == null) {
                exchange.sendResponseHeaders(NOT_FOUND, NO_BODY);
                return;
            }
            byte[] bytes = answer.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            answer.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(
                    answer.status(), bytes.length == 0 ? NO_BODY : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
