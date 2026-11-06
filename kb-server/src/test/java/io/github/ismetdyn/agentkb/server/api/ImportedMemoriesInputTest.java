package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ImportedMemoriesApiTest.COUNT_ALL;
import static io.github.ismetdyn.agentkb.server.api.ImportedMemoriesApiTest.IMPORT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ~/.claude/rules/security.md "Girdi doğrulama", CLAUDE.md "MCP araçları istisna fırlatmaz": a
 * {@code POST /api/v1/signals/imported-memories} body that is not {@code {"memories": [{"source":
 * "claude-code"|"codex", "path", "text"}]}} is 400 {@code {error, hint}}, never a 500, and opens no
 * signal.
 */
class ImportedMemoriesInputTest extends RunningServerTestBase {

    @Autowired private EmbeddedNeo4jStore store;

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{}",
                "{\"memories\": \"not a list\"}",
                "{\"memories\": [{\"source\": \"claude-code\", \"path\": \"x.md\"}]}",
                "{\"memories\": [{\"source\": \"elsewhere\", \"path\": \"x.md\","
                        + " \"text\": \"zz-g14-bad\"}]}",
                "[]",
                "not json"
            })
    void a_body_that_is_not_a_memory_list_is_refused_and_opens_nothing(String body)
            throws Exception {
        long before = count();

        HttpResponse<String> reply = new MaintenanceHttp(port).post(IMPORT, body);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        JsonNode answer = JsonMapper.shared().readTree(reply.body());
        assertThat(answer.path("error").asString("")).isNotBlank();
        assertThat(answer.path("hint").asString("")).isNotBlank();
        assertThat(count()).isEqualTo(before);
    }

    private long count() {
        return store.read(tx -> (Long) tx.execute(COUNT_ALL).next().get("n"));
    }
}
