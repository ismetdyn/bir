package io.github.ismetdyn.agentkb.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server side of the secret contract the client relies on, on a loopback socket: {@code GET
 * /api/v1/secrets/<id>} answers the value as UTF-8 text or 404 with an empty body, {@code PUT}
 * stores the request body and answers 204. Ids keep their slashes in the path. Every request is
 * recorded, so a test can check what the client sent.
 */
final class SecretStub implements AutoCloseable {

    static final String PREFIX = "/api/v1/secrets/";
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int NO_BODY = -1;

    record Request(String method, String rawPath, Map<String, List<String>> headers, String body) {}

    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    SecretStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(PREFIX, this::handle);
        server.start();
    }

    SecretStub with(String id, String value) {
        values.put(id, value);
        return this;
    }

    /** {@code AGENTKB_URL} for the client, the convention of integrations/kb-hook.sh. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    String stored(String id) {
        return values.get(id);
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
            String id = rawPath.substring(PREFIX.length());
            switch (exchange.getRequestMethod()) {
                case "GET" -> answer(exchange, values.get(id));
                case "PUT" -> {
                    values.put(id, body);
                    exchange.sendResponseHeaders(NO_CONTENT, NO_BODY);
                }
                default -> exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, NO_BODY);
            }
        }
    }

    private static void answer(HttpExchange exchange, String value) throws IOException {
        if (value == null) {
            exchange.sendResponseHeaders(NOT_FOUND, NO_BODY);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(OK, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
