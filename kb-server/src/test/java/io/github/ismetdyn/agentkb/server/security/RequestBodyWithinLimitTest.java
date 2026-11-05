package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Framing;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The limit refuses nothing at or below 1 MiB, and a body held back for the size check still
 * arrives whole.
 */
class RequestBodyWithinLimitTest extends RequestBodyTestBase {

    static Stream<Arguments> framingsOfCompleteBodies() {
        return Stream.of(AGENT, CURATOR)
                .flatMap(
                        endpoint ->
                                Stream.of(
                                        arguments(endpoint, Framing.CONTENT_LENGTH),
                                        arguments(endpoint, Framing.CHUNKED)));
    }

    /**
     * Padded initialize requests: a 200 proves the body reached the MCP server whole, in both
     * framings.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("framingsOfCompleteBodies")
    void body_of_exactly_the_limit_reaches_the_mcp_server_intact(String endpoint, Framing framing)
            throws IOException {
        Reply reply = post(endpoint, framing, LIMIT_BYTES, host());

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.body()).contains("serverInfo");
    }

    @ParameterizedTest
    @EnumSource(
            value = Framing.class,
            names = {"CONTENT_LENGTH", "CHUNKED"})
    void body_of_exactly_the_limit_is_not_refused_on_another_path(Framing framing)
            throws IOException {
        Reply reply = post(UNKNOWN_PATH, framing, LIMIT_BYTES, host());

        assertThat(reply.status()).as(reply.body()).isNotEqualTo(413);
    }

    /** The transports read the body differently, so the replay has to serve both of them. */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void small_chunked_body_reaches_the_mcp_server_intact(String endpoint) throws IOException {
        Reply reply = post(endpoint, Framing.CHUNKED, INITIALIZE.length() + 1L, host());

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.headers().get(SESSION_HEADER)).isNotBlank();
    }
}
