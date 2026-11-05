package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** What no error response may show, whichever layer produced it. */
public final class ErrorBodyAssertions {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // A serialized Throwable, at any depth. JSON-RPC's own error.message is contract, so "message"
    // is checked apart.
    private static final Pattern THROWABLE_KEY =
            Pattern.compile(
                    "\"(stackTrace|trace|exception|cause|suppressed|localizedMessage|classLoaderName|className|methodName"
                            + "|fileName|lineNumber)\"\\s*:");
    private static final Pattern INTERNAL_DETAIL =
            Pattern.compile(
                    "(?i)stack ?trace|exception|\\.java\\b|apache tomcat|/home/|/tmp/"
                            + "|\\b(java|javax|jakarta|org|io|com|tools|sun)\\.[a-z0-9_]+\\.[a-z0-9_]+");

    private ErrorBodyAssertions() {}

    static void assertNoLeak(Reply reply) {
        assertNoLeak(reply.body());
        if (reply.body().startsWith("{")) {
            assertThat(JSON.readTree(reply.body()).has("message"))
                    .as("top-level message in: %s", abbreviate(reply.body()))
                    .isFalse();
        }
    }

    public static void assertNoLeak(String body) {
        assertThat(THROWABLE_KEY.matcher(body).find())
                .as("serialized Throwable in: %s", abbreviate(body))
                .isFalse();
        assertThat(INTERNAL_DETAIL.matcher(body).find())
                .as("internal detail in: %s", abbreviate(body))
                .isFalse();
    }

    static String abbreviate(String body) {
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
