package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Invariants 2 and 10, for bodies the MCP SDK does not reject: its other loggers quote
 * client-supplied fields too, at the default level. Only the absence is asserted; how these events
 * stay out of the log is not this test's concern.
 */
@ExtendWith(OutputCaptureExtension.class)
class PayloadLogLeakTest extends SecurityTestBase {

    private static final String SENTINEL = "sentinel-logged-field-91bd";

    static Stream<Arguments> initializeRequests() {
        return onBothEndpoints(
                INITIALIZE.replace("agentkb-test", SENTINEL),
                INITIALIZE.replace("2025-06-18", SENTINEL));
    }

    static Stream<Arguments> messagesInsideSession() {
        return onBothEndpoints(
                "{\"jsonrpc\":\"2.0\",\"method\":\"" + SENTINEL + "\"}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\""
                        + SENTINEL
                        + "\",\"progress\":1}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"reason\":\""
                        + SENTINEL
                        + "\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + SENTINEL + "\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\""
                        + SENTINEL
                        + "\",\"error\":{\"code\":1,\"message\":\"x\"}}");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("initializeRequests")
    void initialize_fields_are_not_logged(String endpoint, String request, CapturedOutput output)
            throws IOException {
        Reply reply = postJson(endpoint, request, host());

        assertThat(reply.status()).as("the server processed the request").isEqualTo(200);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("messagesInsideSession")
    void messages_inside_a_session_are_not_logged(
            String endpoint, String message, CapturedOutput output) throws IOException {
        String session = openSession(endpoint);

        postJson(endpoint, message, host(), SESSION_HEADER + ": " + session);

        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
