package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Invariants 2 and 10: the MCP transports log a rejected body, in the message and in the exception
 * text. Which part of a malformed body is the secret cannot be known, so the event stays visible
 * and all of its text is replaced.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransportLogMaskingTest extends SecurityTestBase {

    private static final String SENTINEL = "sentinel-logged-payload-5c1e";
    private static final String MASK = "*****";
    // Ends of the logger names; Boot's default pattern cuts the start of a long logger name.
    private static final String AGENT_TRANSPORT_LOGGER = "WebMvcStreamableServerTransportProvider";
    private static final String CURATOR_TRANSPORT_LOGGER =
            "ServletStreamableServerTransportProvider";
    private static final Pattern LOG_LINE_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    // Nothing of the message may stand between the logger name and the mask.
    private static final Pattern MASKED_ENDING =
            Pattern.compile("TransportProvider\\P{Alnum}*\\*{5}$");
    private static final List<String> TEXT_OF_THE_EVENT =
            List.of(
                    "Failed to",
                    "Cannot deserialize",
                    "Cannot construct",
                    "Unrecognized token",
                    "Exception",
                    "No location information",
                    "Caused by");

    // The capture also holds the application start when this class happens to boot the shared
    // context.
    private int capturedBeforeTest;

    @BeforeEach
    void markCapturedOutput(CapturedOutput output) {
        capturedBeforeTest = output.length();
    }

    static Stream<Arguments> bodiesRejectedWithoutSession() {
        return onBothEndpoints(
                // Not JSON: the parser quotes the token it stopped at.
                "{\"id\":" + SENTINEL + "}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"" + SENTINEL,
                // JSON but not JSON-RPC: the transport quotes the whole message.
                "{\"not_a_jsonrpc_message\":\"" + SENTINEL + "\"}",
                // Params as a string: the exception text quotes the value.
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":\""
                        + SENTINEL
                        + "\"}");
    }

    static Stream<Arguments> paramsRejectedInsideSession() {
        return onBothEndpoints(
                "\"" + SENTINEL + "\"",
                "[\"" + SENTINEL + "\"]",
                "{\"name\":{\"x\":\"" + SENTINEL + "\"}}",
                "{\"name\":\"ping\",\"arguments\":\"" + SENTINEL + "\"}");
    }

    /**
     * Stays green with or without masking: what the tests below find missing in the output, the
     * capture would have seen.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void capture_sees_the_transport_logger(String endpoint, CapturedOutput output)
            throws IOException {
        postJson(endpoint, "{\"not_a_jsonrpc_message\":1}", host());

        assertThat(transportLines(endpoint, output)).isNotEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("bodiesRejectedWithoutSession")
    void rejected_body_is_logged_masked(String endpoint, String body, CapturedOutput output)
            throws IOException {
        postJson(endpoint, body, host());

        assertMaskedEventOnly(endpoint, output);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("paramsRejectedInsideSession")
    void params_rejected_inside_a_session_are_logged_masked(
            String endpoint, String params, CapturedOutput output) throws IOException {
        String session = openSession(endpoint);
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                        + params
                        + "}";

        postJson(endpoint, request, host(), SESSION_HEADER + ": " + session);

        assertMaskedEventOnly(endpoint, output);
    }

    /**
     * The masking pattern must not spread: everyone else keeps the message, the stack trace and
     * Boot's format.
     */
    @Test
    void other_loggers_keep_the_default_format_and_their_stack_trace(CapturedOutput output) {
        LoggerFactory.getLogger("agentkb.test.control")
                .error("control-message", new IllegalStateException("control-cause"));

        assertThat(output.getAll())
                .containsPattern(
                        "ERROR \\d+ --- \\[.*\\] agentkb\\.test\\.control\\s*: control-message")
                .contains("java.lang.IllegalStateException: control-cause")
                .contains("\tat " + getClass().getName());
    }

    private void assertMaskedEventOnly(String endpoint, CapturedOutput output) {
        List<String> lines = transportLines(endpoint, output);

        assertThat(lines).as("the event stays visible in: %s", output.getAll()).isNotEmpty();
        assertThat(lines)
                .allSatisfy(
                        line -> {
                            assertThat(line).contains("ERROR").contains(MASK);
                            assertThat(MASKED_ENDING.matcher(line.strip()).find())
                                    .as(line)
                                    .isTrue();
                        });
        assertThat(output.getAll()).doesNotContain(SENTINEL);
        assertThat(output.getAll().substring(capturedBeforeTest)).doesNotContain(TEXT_OF_THE_EVENT);
    }

    private static List<String> transportLines(String endpoint, CapturedOutput output) {
        String logger = AGENT.equals(endpoint) ? AGENT_TRANSPORT_LOGGER : CURATOR_TRANSPORT_LOGGER;
        return output.getAll()
                .lines()
                .filter(line -> LOG_LINE_START.matcher(line).matches() && line.contains(logger))
                .toList();
    }
}
