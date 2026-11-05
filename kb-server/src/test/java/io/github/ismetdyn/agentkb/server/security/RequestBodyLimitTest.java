package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Framing;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A body larger than 1 MiB must never reach the heap of the JVM hosting Neo4j: /mcp otherwise reads
 * it whole, and eight concurrent 16 MiB bodies under -Xmx512m ended in OutOfMemoryError.
 * /mcp/curator is in every case too: its SDK refuses only above 16 MiB, so a 413 at 1 MiB + 1 there
 * can only come from our filter.
 */
class RequestBodyLimitTest extends RequestBodyTestBase {

    /**
     * The last two values wrap around when squeezed through an int: to a negative number and to 1.
     */
    static Stream<Arguments> declaredLengthsOverLimit() {
        return onEveryPath(OVER_LIMIT_BYTES, 1L << 31, (1L << 32) + 1);
    }

    static Stream<Arguments> framingsOfOversizedBodies() {
        return onEveryPath(Framing.CONTENT_LENGTH, Framing.CHUNKED, Framing.CHUNKED_UNTERMINATED);
    }

    /** Nothing but the headers is sent, so a 413 proves the decision did not read the body. */
    @ParameterizedTest(name = "{0} Content-Length: {1}")
    @MethodSource("declaredLengthsOverLimit")
    void declared_length_over_the_limit_is_refused_without_reading_the_body(
            String path, long declared) throws IOException {
        Reply reply = post(path, Framing.DECLARED_ONLY, declared, host());

        assertTooLarge(reply);
    }

    /**
     * The unterminated framing keeps the connection open: a filter waiting for the end of the body
     * would time out here.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("framingsOfOversizedBodies")
    void body_over_the_limit_is_refused(String path, Framing framing) throws IOException {
        Reply reply = post(path, framing, OVER_LIMIT_BYTES, host());

        assertTooLarge(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR, UNKNOWN_PATH})
    void chunked_body_cannot_hide_behind_a_small_declared_length(String path) throws IOException {
        Reply reply =
                post(
                        path,
                        Framing.CHUNKED_UNTERMINATED,
                        OVER_LIMIT_BYTES,
                        host(),
                        "Content-Length: 5");

        assertThat(reply.status()).as(reply.body()).isIn(400, 413);
        assertNoMcpAnswer(reply);
    }

    /**
     * Broken framing is the client's fault and reading it must not turn into a server error page.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR, UNKNOWN_PATH})
    void malformed_chunk_framing_is_a_client_error(String path) throws IOException {
        Reply reply = post(path, Framing.CHUNKED_MALFORMED, 0, host());

        assertThat(reply.status()).as(reply.body()).isBetween(400, 499);
        assertNoLeak(reply);
        assertNoMcpAnswer(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void server_keeps_answering_after_refusing_a_body(String endpoint) throws IOException {
        assertTooLarge(post(endpoint, Framing.CONTENT_LENGTH, OVER_LIMIT_BYTES, host()));

        assertThat(initialize(endpoint, host()).status()).isEqualTo(200);
    }
}
