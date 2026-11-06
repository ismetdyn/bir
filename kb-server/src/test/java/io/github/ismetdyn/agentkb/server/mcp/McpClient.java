package io.github.ismetdyn.agentkb.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One MCP session on the agent endpoint over real HTTP, the way Claude Code and Codex speak to it.
 * Arguments are serialized by Jackson, so Turkish letters and quotes reach the server unchanged.
 */
public final class McpClient {

    private static final String AGENT_ENDPOINT = "/mcp";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final URI uri;
    private final String sessionId;

    private McpClient(int port) throws IOException, InterruptedException {
        this.uri = URI.create("http://127.0.0.1:" + port + AGENT_ENDPOINT);
        HttpResponse<String> initialized =
                send(
                        null,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                nextId.getAndIncrement(),
                                "method",
                                "initialize",
                                "params",
                                Map.of(
                                        "protocolVersion", PROTOCOL_VERSION,
                                        "capabilities", Map.of(),
                                        "clientInfo",
                                                Map.of(
                                                        "name", "agentkb-test",
                                                        "version", "0"))));
        assertThat(initialized.statusCode()).as(initialized.body()).isEqualTo(200);
        this.sessionId = initialized.headers().firstValue(SESSION_HEADER).orElseThrow();
        HttpResponse<String> notified =
                send(sessionId, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        assertThat(notified.statusCode()).as(notified.body()).isEqualTo(202);
    }

    public static McpClient open(int port) throws IOException, InterruptedException {
        return new McpClient(port);
    }

    /** The JSON-RPC reply of tools/list. */
    public JsonNode toolsList() throws IOException, InterruptedException {
        return rpc(Map.of("method", "tools/list"));
    }

    /** The raw JSON-RPC reply of tools/call, protocol errors included. */
    public JsonNode call(String tool, Map<String, ?> arguments)
            throws IOException, InterruptedException {
        return call(tool, arguments, Map.of());
    }

    /**
     * tools/call with {@code params._meta}, as clients send it: Codex 0.161.0 puts its session id
     * there ({@code sessionId}, {@code threadId}, {@code x-codex-turn-metadata}), Claude Code
     * 2.1.318 only {@code claudecode/toolUseId} (measured 2026-11-06, ADR-0023 decision 6).
     */
    public JsonNode call(String tool, Map<String, ?> arguments, Map<String, ?> meta)
            throws IOException, InterruptedException {
        Map<String, Object> params = new HashMap<>(Map.of("name", tool, "arguments", arguments));
        if (!meta.isEmpty()) {
            params.put("_meta", meta);
        }
        return rpc(Map.of("method", "tools/call", "params", params));
    }

    /**
     * The text a tool returned. A JSON-RPC error fails the test: MCP tools never throw, they answer
     * (with {@code {error, hint}} when something is wrong).
     */
    public String text(String tool, Map<String, ?> arguments)
            throws IOException, InterruptedException {
        return text(tool, arguments, Map.of());
    }

    /** {@link #text(String, Map)} with {@code params._meta}. */
    public String text(String tool, Map<String, ?> arguments, Map<String, ?> meta)
            throws IOException, InterruptedException {
        JsonNode reply = call(tool, arguments, meta);
        assertThat(reply.has("error"))
                .as("protocol error instead of an answer: %s", reply)
                .isFalse();
        return reply.at("/result/content/0/text").asString("");
    }

    /** kb_search with {@code query} plus optional {@code type}, {@code env}, {@code limit}. */
    public String search(String query, Map<String, ?> filters)
            throws IOException, InterruptedException {
        Map<String, Object> arguments = new HashMap<>(filters);
        arguments.put("query", query);
        return text("kb_search", arguments);
    }

    private JsonNode rpc(Map<String, ?> message) throws IOException, InterruptedException {
        Map<String, Object> request = new HashMap<>(message);
        request.put("jsonrpc", "2.0");
        request.put("id", nextId.getAndIncrement());
        HttpResponse<String> response = send(sessionId, request);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(ssePayload(response.body()));
    }

    /** Streamable HTTP frames a reply as an SSE {@code data:} line, with or without a space. */
    private static String ssePayload(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .findFirst()
                .orElse(body);
    }

    private HttpResponse<String> send(String session, Map<String, ?> message)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(message)));
        if (session != null) {
            request.header(SESSION_HEADER, session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
