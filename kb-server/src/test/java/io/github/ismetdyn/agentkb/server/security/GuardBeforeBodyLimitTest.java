package io.github.ismetdyn.agentkb.server.security;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Framing;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The guard runs first, so these answers cannot depend on the body: not its size, and not even its
 * arrival.
 */
class GuardBeforeBodyLimitTest extends RequestBodyTestBase {

    static Stream<Arguments> framingsOfForgedRequests() {
        return onEveryPath(
                Framing.DECLARED_ONLY,
                Framing.CONTENT_LENGTH,
                Framing.CHUNKED,
                Framing.CHUNKED_UNTERMINATED);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("framingsOfForgedRequests")
    void forged_host_is_forbidden_whatever_the_size(String path, Framing framing)
            throws IOException {
        Reply reply = post(path, framing, OVER_LIMIT_BYTES, "Host: attacker.example:" + port);

        assertForbidden(reply);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("framingsOfForgedRequests")
    void origin_is_forbidden_whatever_the_size(String path, Framing framing) throws IOException {
        Reply reply =
                post(path, framing, OVER_LIMIT_BYTES, host(), "Origin: http://attacker.example");

        assertForbidden(reply);
    }
}
