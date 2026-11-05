package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * kb_remember never throws; a rejected write answers {@code {error, hint}} and writes nothing
 * (docs/MCP-TOOLS.md, SPEC §7 rules 8 and 10). A secret-looking value is not echoed back (invariant
 * 2). Each row names a fresh zz-g7-* entity, so "nothing written" is "no such entity".
 */
class RememberRejectionTest extends RunningServerTestBase {

    private static final String SECRET = "password=" + "Sup3rS3cretG7";
    private static final String TOKEN = "ghp" + "_9z8Y7x6W5v4U3t2S1r0Q9p8O7n6M5l4K3j2I";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EmbeddedNeo4jStore store;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    private static Stream<Arguments> rejected() {
        return Stream.of(
                arguments(
                        "secret in a key = value",
                        "zz-g7-rej-secret",
                        "server",
                        fact("access", SECRET),
                        "kb secret set"),
                arguments(
                        "token as a value",
                        "zz-g7-rej-token",
                        "server",
                        fact("access", TOKEN),
                        "kb secret set"),
                arguments(
                        "text fact over 500 characters",
                        "zz-g7-rej-long",
                        "server",
                        fact("role", "ş".repeat(501)),
                        "kb doc add"),
                arguments(
                        "unknown fact key",
                        "zz-g7-rej-key",
                        "server",
                        fact("colour", "blue"),
                        "kb_schema"),
                arguments(
                        "unknown entity type",
                        "zz-g7-rej-type",
                        "spaceship",
                        fact("os", "rhel 9"),
                        "kb_schema"),
                arguments(
                        "unknown entity and no type",
                        "zz-g7-rej-untyped",
                        null,
                        fact("os", "rhel 9"),
                        "type"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void a_rejected_write_answers_error_and_hint_and_writes_nothing(
            String reason, String entity, String type, Map<String, String> fact, String hintNames)
            throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("entity", entity);
        if (type != null) {
            arguments.put("type", type);
        }
        arguments.put("facts", List.of(fact));
        arguments.put("source", Map.of("kind", "code"));

        String reply = mcp.text("kb_remember", arguments);

        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString()).as(reason).contains(hintNames);
        assertThat(reply).doesNotContain(SECRET).doesNotContain(TOKEN);
        assertThat(entityCount(entity)).as(reason + ": nothing written").isZero();
    }

    private static Map<String, String> fact(String key, String value) {
        return Map.of("key", key, "value", value, "env", "prod");
    }

    private long entityCount(String id) {
        return store.read(
                tx ->
                        (Long)
                                tx.execute(
                                                "MATCH (n {id: $id}) RETURN count(n) AS n",
                                                Map.of("id", id))
                                        .next()
                                        .get("n"));
    }
}
