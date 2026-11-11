package io.github.ismetdyn.agentkb.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server side of {@code /hooks/} for {@code kb hook}, on a loopback socket: answers every
 * request with {@code status} and {@link #ANSWER} after {@code delay}, and records it.
 */
final class HookStub implements AutoCloseable {

    static final String ANSWER =
            "{\"hookSpecificOutput\":{\"additionalContext\":\"brief: ornek-proje\"}}";

    record Request(String method, String path, String query, String type, String body) {}

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final int status;
    private final Duration delay;

    HookStub(int status, Duration delay) throws IOException {
        this.status = status;
        this.delay = delay;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hooks/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.add(
                    new Request(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getRawPath(),
                            exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8)));
            Thread.sleep(delay);
            byte[] body = ANSWER.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
