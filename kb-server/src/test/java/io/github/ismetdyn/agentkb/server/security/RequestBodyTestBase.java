package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Framing;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Sized bodies for the request body limit. The limit is written here again on purpose, as the
 * required value.
 */
abstract class RequestBodyTestBase extends SecurityTestBase {

    protected static final long LIMIT_BYTES = 1024L * 1024;
    protected static final long OVER_LIMIT_BYTES = LIMIT_BYTES + 1;
    protected static final String UNKNOWN_PATH = "/nope";

    protected static Stream<Arguments> onEveryPath(Object... values) {
        return Stream.of(AGENT, CURATOR, UNKNOWN_PATH)
                .flatMap(path -> Stream.of(values).map(value -> arguments(path, value)));
    }

    protected Reply post(String path, Framing framing, long length, String... headerLines)
            throws IOException {
        String[] lines =
                Stream.concat(Stream.of(headerLines), Stream.of(CONTENT_TYPE, ACCEPT))
                        .toArray(String[]::new);
        return RawHttp.sendPadded(port, framing, path, INITIALIZE, length, lines);
    }

    protected static void assertTooLarge(Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(413);
        assertNoLeak(reply);
        assertNoMcpAnswer(reply);
        assertThat(reply.body()).doesNotContain("clientInfo");
    }
}
