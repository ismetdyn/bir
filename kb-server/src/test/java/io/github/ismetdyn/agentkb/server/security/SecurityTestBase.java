package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** Raw-socket requests against the running server, shared by the tests of the security boundary. */
abstract class SecurityTestBase extends RunningServerTestBase {

    protected static final String AGENT = "/mcp";
    protected static final String CURATOR = "/mcp/curator";
    protected static final String SESSION_HEADER = "Mcp-Session-Id";
    protected static final String CONTENT_TYPE = "Content-Type: application/json";
    protected static final String ACCEPT = "Accept: application/json, text/event-stream";
    protected static final String TOOLS_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    protected static final String INITIALIZE =
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},\
            "clientInfo":{"name":"agentkb-test","version":"0"}}}""";
    protected static final String INITIALIZED =
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    // Replaced by the random port of the running server; argument sources are static and cannot see
    // it.
    protected static final String PORT = "{port}";

    protected static Stream<Arguments> onBothEndpoints(String... values) {
        return Stream.of(AGENT, CURATOR)
                .flatMap(endpoint -> Stream.of(values).map(v -> Arguments.of(endpoint, v)));
    }

    protected Reply initialize(String endpoint, String... headerBlocks) throws IOException {
        Stream<String> given = Stream.of(headerBlocks).flatMap(block -> block.lines());
        String[] lines =
                Stream.concat(given, Stream.of(CONTENT_TYPE, ACCEPT)).toArray(String[]::new);
        return RawHttp.send(port, HTTP_1_1, "POST", endpoint, INITIALIZE, lines);
    }

    protected Reply postJson(String endpoint, String body, String... headerLines)
            throws IOException {
        String[] lines =
                Stream.concat(Stream.of(headerLines), Stream.of(CONTENT_TYPE, ACCEPT))
                        .toArray(String[]::new);
        return RawHttp.send(port, HTTP_1_1, "POST", endpoint, body, lines);
    }

    protected String openSession(String endpoint) throws IOException {
        Reply initialized =
                RawHttp.send(
                        port, HTTP_1_1, "POST", endpoint, INITIALIZE, host(), CONTENT_TYPE, ACCEPT);
        assertThat(initialized.status()).as(initialized.body()).isEqualTo(200);
        String session = initialized.headers().get(SESSION_HEADER);
        Reply notified =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        endpoint,
                        INITIALIZED,
                        host(),
                        CONTENT_TYPE,
                        ACCEPT,
                        SESSION_HEADER + ": " + session);
        assertThat(notified.status()).as(notified.body()).isEqualTo(202);
        return session;
    }

    protected String host() {
        return "Host: 127.0.0.1:" + port;
    }

    protected String withPort(String template) {
        return template.replace(PORT, String.valueOf(port));
    }

    protected static void assertForbidden(Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(403);
        assertNoMcpAnswer(reply);
    }

    protected static void assertNoMcpAnswer(Reply reply) {
        assertThat(reply.hasHeader(SESSION_HEADER)).isFalse();
        assertThat(reply.body()).doesNotContain("serverInfo");
    }
}
