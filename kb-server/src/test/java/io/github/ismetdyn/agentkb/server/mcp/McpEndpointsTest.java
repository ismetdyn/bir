package io.github.ismetdyn.agentkb.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** K5: both MCP endpoints, spoken to over real HTTP the way an MCP client does, stay separate. */
class McpEndpointsTest extends RunningServerTestBase {

    private static final String AGENT_ENDPOINT = "/mcp";
    private static final String CURATOR_ENDPOINT = "/mcp/curator";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String UNKNOWN_SESSION = "00000000-0000-0000-0000-000000000000";
    private static final String TOOLS_LIST_REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void each_endpoint_identifies_itself_as_a_separate_server() throws Exception {
        assertThat(initialize(AGENT_ENDPOINT).json().at("/result/serverInfo/name").asString())
                .isEqualTo("agentkb");
        assertThat(initialize(CURATOR_ENDPOINT).json().at("/result/serverInfo/name").asString())
                .isEqualTo("agentkb-curator");
    }

    @Test
    void agent_endpoint_lists_ping_and_not_curator_tool() throws Exception {
        assertThat(toolNames(openSession(AGENT_ENDPOINT))).containsExactly("ping");
    }

    @Test
    void agent_endpoint_answers_ping_with_pong() throws Exception {
        JsonNode reply = callTool(openSession(AGENT_ENDPOINT), "ping");

        assertThat(isToolError(reply)).as(reply.toString()).isFalse();
        assertThat(firstText(reply)).isEqualTo("pong");
    }

    @Test
    void agent_endpoint_rejects_curator_tool_call() throws Exception {
        JsonNode reply = callTool(openSession(AGENT_ENDPOINT), "cur_ping");

        assertThat(isToolError(reply)).as(reply.toString()).isTrue();
        assertThat(firstText(reply)).isNotEqualTo("cur_pong");
    }

    @Test
    void curator_endpoint_lists_cur_ping_and_not_agent_tool() throws Exception {
        assertThat(toolNames(openSession(CURATOR_ENDPOINT))).containsExactly("cur_ping");
    }

    @Test
    void curator_endpoint_answers_cur_ping_with_cur_pong() throws Exception {
        JsonNode reply = callTool(openSession(CURATOR_ENDPOINT), "cur_ping");

        assertThat(isToolError(reply)).as(reply.toString()).isFalse();
        assertThat(firstText(reply)).isEqualTo("cur_pong");
    }

    @Test
    void curator_endpoint_rejects_agent_tool_call() throws Exception {
        JsonNode reply = callTool(openSession(CURATOR_ENDPOINT), "ping");

        assertThat(isToolError(reply)).as(reply.toString()).isTrue();
        assertThat(firstText(reply)).isNotEqualTo("pong");
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT_ENDPOINT, CURATOR_ENDPOINT})
    void request_without_session_is_rejected(String endpoint) throws Exception {
        Reply reply = post(endpoint, null, TOOLS_LIST_REQUEST);

        assertThat(reply.status()).as(reply.body()).isEqualTo(400);
        assertThat(reply.body()).doesNotContain("\"tools\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT_ENDPOINT, CURATOR_ENDPOINT})
    void request_with_unknown_session_is_rejected(String endpoint) throws Exception {
        Reply reply = post(endpoint, UNKNOWN_SESSION, TOOLS_LIST_REQUEST);

        assertThat(reply.status()).as(reply.body()).isEqualTo(404);
        assertThat(reply.body()).doesNotContain("\"tools\"");
    }

    @Test
    void session_of_one_endpoint_is_not_valid_on_the_other() throws Exception {
        Reply onCurator = post(CURATOR_ENDPOINT, openSession(AGENT_ENDPOINT).id(), TOOLS_LIST_REQUEST);
        Reply onAgent = post(AGENT_ENDPOINT, openSession(CURATOR_ENDPOINT).id(), TOOLS_LIST_REQUEST);

        assertThat(onCurator.status()).as(onCurator.body()).isEqualTo(404);
        assertThat(onAgent.status()).as(onAgent.body()).isEqualTo(404);
    }

    private record Session(String endpoint, String id) {}

    private record Reply(int status, String sessionId, String body) {

        /** Streamable HTTP frames a JSON-RPC reply as an SSE {@code data:} line, with or without a space after it. */
        JsonNode json() {
            String payload = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip())
                    .findFirst()
                    .orElse(body);
            return JSON.readTree(payload);
        }
    }

    private Reply initialize(String endpoint) throws IOException, InterruptedException {
        Reply reply = post(endpoint, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},\
                "clientInfo":{"name":"agentkb-test","version":"0"}}}""".formatted(PROTOCOL_VERSION));
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.sessionId()).isNotBlank();
        return reply;
    }

    private Session openSession(String endpoint) throws IOException, InterruptedException {
        Session session = new Session(endpoint, initialize(endpoint).sessionId());
        Reply initialized = post(endpoint, session.id(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(initialized.status()).as(initialized.body()).isEqualTo(202);
        return session;
    }

    private List<String> toolNames(Session session) throws IOException, InterruptedException {
        Reply reply = post(session.endpoint(), session.id(), TOOLS_LIST_REQUEST);
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        reply.json().at("/result/tools").forEach(tool -> names.add(tool.get("name").asString()));
        return names;
    }

    private JsonNode callTool(Session session, String toolName) throws IOException, InterruptedException {
        return post(session.endpoint(), session.id(), """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"%s","arguments":{}}}"""
                .formatted(toolName))
                .json();
    }

    /** MCP allows both forms: a JSON-RPC error or a result flagged {@code isError}. */
    private static boolean isToolError(JsonNode reply) {
        return reply.has("error") || reply.at("/result/isError").asBoolean(false);
    }

    private static String firstText(JsonNode reply) {
        return reply.at("/result/content/0/text").asString("");
    }

    private Reply post(String endpoint, String sessionId, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header(SESSION_HEADER, sessionId);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(
                response.statusCode(),
                response.headers().firstValue(SESSION_HEADER).orElse(null),
                response.body());
    }
}
