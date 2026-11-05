package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Framing;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0022 §17 on the paths where the container itself treats the body differently from a plain
 * JSON POST.
 */
class RequestBodyEdgeCasesTest extends RequestBodyTestBase {

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 (\\d{3}) ");
    private static final String MCP_ANSWER = "serverInfo";

    static Stream<Arguments> oversizedForms() {
        return Stream.of("multipart/form-data; boundary=x", "application/x-www-form-urlencoded")
                .flatMap(
                        type ->
                                Stream.of(
                                                Framing.DECLARED_ONLY,
                                                Framing.CONTENT_LENGTH,
                                                Framing.CHUNKED)
                                        .flatMap(
                                                framing ->
                                                        Stream.of(AGENT, CURATOR, UNKNOWN_PATH)
                                                                .map(
                                                                        path ->
                                                                                arguments(
                                                                                        path, type,
                                                                                        framing))));
    }

    static Stream<Arguments> oversizedBodiesAfterContinue() {
        return onEveryPath(Framing.DECLARED_ONLY, Framing.CONTENT_LENGTH, Framing.CHUNKED);
    }

    /**
     * Tomcat parses these types by itself once a servlet asks for parameters or parts; the limit
     * must come first.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("oversizedForms")
    void form_over_the_limit_is_refused(String path, String contentType, Framing framing)
            throws IOException {
        assertTooLarge(
                RawHttp.sendPadded(
                        port,
                        framing,
                        path,
                        "a=b",
                        OVER_LIMIT_BYTES,
                        host(),
                        "Content-Type: " + contentType,
                        ACCEPT));
    }

    /**
     * Tomcat answers 100 before any filter runs, so the client does send the body: the final status
     * still decides.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("oversizedBodiesAfterContinue")
    void expect_continue_does_not_get_a_body_past_the_limit(String path, Framing framing)
            throws IOException {
        assertTooLarge(post(path, framing, OVER_LIMIT_BYTES, host(), "Expect: 100-continue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void transport_reads_only_the_declared_length(String endpoint) throws IOException {
        int cutInsideTheJson = 5;
        String request = headOf(endpoint, cutInsideTheJson) + INITIALIZE;

        String replies = RawHttp.exchange(port, request, request.length());

        assertThat(statusesIn(replies)).containsExactly(400);
        assertThat(replies).doesNotContain(MCP_ANSWER);
    }

    /**
     * Once the first request succeeds the connection stays open, and the remaining bytes are a
     * request of their own.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void request_past_a_short_content_length_still_meets_the_guard(String endpoint)
            throws IOException {
        String forged =
                "POST "
                        + endpoint
                        + " HTTP/1.1\r\nHost: attacker.example:"
                        + port
                        + "\r\n"
                        + CONTENT_TYPE
                        + "\r\n"
                        + ACCEPT
                        + "\r\nContent-Length: "
                        + INITIALIZE.length()
                        + "\r\nConnection: close\r\n\r\n"
                        + INITIALIZE;
        String request = headOf(endpoint, INITIALIZE.length()) + INITIALIZE + forged;

        String replies = RawHttp.exchange(port, request, request.length());

        assertThat(statusesIn(replies)).containsExactly(200, 403);
        assertThat(Pattern.compile(MCP_ANSWER).matcher(replies).results()).hasSize(1);
    }

    /**
     * Oversized bytes past a short Content-Length belong to no request, so they must not reach a
     * handler either.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void oversized_remainder_past_a_short_content_length_is_not_a_request(String endpoint)
            throws IOException {
        String request = headOf(endpoint, INITIALIZE.length()) + INITIALIZE;

        String replies = RawHttp.exchange(port, request, request.length() + OVER_LIMIT_BYTES);

        assertThat(statusesIn(replies)).containsExactly(200, 400);
        assertThat(initialize(endpoint, host()).status()).isEqualTo(200);
    }

    /**
     * No {@code Connection: close}: the server has to be left free to read on after this request.
     */
    private String headOf(String endpoint, int declaredLength) {
        return "POST "
                + endpoint
                + " HTTP/1.1\r\n"
                + host()
                + "\r\n"
                + CONTENT_TYPE
                + "\r\n"
                + ACCEPT
                + "\r\nContent-Length: "
                + declaredLength
                + "\r\n\r\n";
    }

    private static List<Integer> statusesIn(String replies) {
        return STATUS_LINE
                .matcher(replies)
                .results()
                .map(status -> Integer.valueOf(status.group(1)))
                .toList();
    }
}
