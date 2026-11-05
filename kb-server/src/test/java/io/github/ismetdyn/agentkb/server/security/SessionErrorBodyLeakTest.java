package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plan G1: inside a session JSON-RPC errors travel with HTTP 200, so a status check alone would not
 * see them.
 */
class SessionErrorBodyLeakTest extends SecurityTestBase {

    private static final String REJECTED_VALUE = "sentinel-rejected-payload-7f3a";

    /**
     * Payload that fails conversion must come back without internals and without the rejected
     * value, which may be a secret (invariant 2).
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sessionRequestsWithMalformedPayload")
    void malformed_payload_inside_a_session_is_not_echoed(String endpoint, String request)
            throws IOException {
        Reply reply = sendInSession(endpoint, request);

        assertThat(reply.body()).doesNotContain(REJECTED_VALUE);
        assertNoLeak(reply.body());
        assertNoLeak(ssePayload(reply.body()));
    }

    static Stream<Arguments> sessionRequestsWithMalformedPayload() {
        return Stream.of("/mcp", "/mcp/curator")
                .flatMap(
                        endpoint ->
                                Stream.of(
                                                toolsCall(
                                                        "{\"name\":{\"x\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}}"),
                                                toolsCall("\"" + REJECTED_VALUE + "\""),
                                                toolsCall("[\"" + REJECTED_VALUE + "\"]"),
                                                toolsCall("5"),
                                                toolsCall("null"),
                                                toolsCall(
                                                        "{\"name\":\"ping\",\"arguments\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}"),
                                                toolsCall(
                                                        "{\"name\":\"cur_ping\",\"arguments\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}"),
                                                toolsCall(
                                                        "{\"name\":\"ping\",\"arguments\":{},\"_meta\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}"),
                                                toolsCall(
                                                        "{\"name\":\"ping\",\"arguments\":{\"x\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}}"),
                                                toolsCall(
                                                        "{\"name\":\"cur_ping\",\"arguments\":{\"x\":\""
                                                                + REJECTED_VALUE
                                                                + "\"}}"))
                                        .map(request -> Arguments.of(endpoint, request)));
    }

    /**
     * The SDK quotes an unknown method, tool or resource name back to its sender, which is contract
     * text and not a payload. These stay free of internals; the echo itself is recorded in the G1
     * review, not asserted here.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sessionRequestsWithUnknownName")
    void unknown_name_inside_a_session_carries_no_internal_detail(String endpoint, String request)
            throws IOException {
        Reply reply = sendInSession(endpoint, request);

        assertThat(ssePayload(reply.body()))
                .as("a JSON-RPC error must reach the client")
                .contains("\"error\"");
        assertNoLeak(ssePayload(reply.body()));
    }

    static Stream<Arguments> sessionRequestsWithUnknownName() {
        return Stream.of("/mcp", "/mcp/curator")
                .flatMap(
                        endpoint ->
                                Stream.of(
                                                toolsCall(
                                                        "{\"name\":\""
                                                                + REJECTED_VALUE
                                                                + "\",\"arguments\":{}}"),
                                                toolsCall("{\"arguments\":{}}"),
                                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\""
                                                        + REJECTED_VALUE
                                                        + "\"}",
                                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\""
                                                        + REJECTED_VALUE
                                                        + "\"}}")
                                        .map(request -> Arguments.of(endpoint, request)));
    }

    private static String toolsCall(String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":" + params + "}";
    }

    private Reply sendInSession(String endpoint, String request) throws IOException {
        String session = openSession(endpoint);
        return RawHttp.send(
                port,
                HTTP_1_1,
                "POST",
                endpoint,
                request,
                host(),
                CONTENT_TYPE,
                ACCEPT,
                SESSION_HEADER + ": " + session);
    }

    /**
     * The JSON-RPC message of a text/event-stream reply; the raw body when the reply is not framed
     * as SSE.
     */
    private static String ssePayload(String body) {
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).strip())
                .findFirst()
                .orElse(body);
    }
}
