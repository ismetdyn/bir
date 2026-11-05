package io.github.ismetdyn.agentkb.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A loopback server that answers {@code <METHOD> <path>} with a fixed status and JSON body, and
 * records every request, so a test sees exactly what {@code kb} sent and how it treats the answer.
 * An unknown method and path answers 404.
 */
final class ApiStub implements AutoCloseable {

    private static final int NOT_FOUND = 404;
    private static final int NO_BODY = -1;

    record Request(String method, String rawPath, Map<String, List<String>> headers, String body) {}

    private record Answer(int status, String json) {}

    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    ApiStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    ApiStub answer(String method, String path, int status, String json) {
        answers.put(method + " " + path, new Answer(status, json));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
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
            String body =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String rawPath = exchange.getRequestURI().getRawPath();
            requests.add(
                    new Request(
                            exchange.getRequestMethod(),
                            rawPath,
                            Map.copyOf(exchange.getRequestHeaders()),
                            body));
            Answer answer = answers.get(exchange.getRequestMethod() + " " + rawPath);
            if (answer == null) {
                exchange.sendResponseHeaders(NOT_FOUND, NO_BODY);
                return;
            }
            byte[] bytes = answer.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
