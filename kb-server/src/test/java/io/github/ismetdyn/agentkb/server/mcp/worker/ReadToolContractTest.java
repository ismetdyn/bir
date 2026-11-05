package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertNoInternalDetail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What every read tool promises regardless of data: it is announced read-only (ADR-0018, Codex asks
 * for approval otherwise), and it never throws, answering {@code {error, hint}} without internal
 * detail (CLAUDE.md "Kod kuralları", docs/MCP-TOOLS.md).
 */
class ReadToolContractTest extends RunningServerTestBase {

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "kb_brief",
                "kb_search",
                "kb_read",
                "kb_related",
                "kb_cypher",
                "kb_schema",
                "kb_stale"
            })
    void a_read_tool_is_announced_read_only(String name) throws Exception {
        JsonNode tools = mcp.toolsList().at("/result/tools");
        JsonNode tool = null;
        for (JsonNode candidate : tools) {
            if (name.equals(candidate.path("name").asString(""))) {
                tool = candidate;
            }
        }

        assertThat(tool).as("%s not in tools/list: %s", name, tools).isNotNull();
        assertThat(tool.at("/annotations/readOnlyHint").asBoolean(false))
                .as(tool.toString())
                .isTrue();
        // An absent hint means the MCP default (destructive, not idempotent); Codex then asks for
        // approval and 'codex exec' with approval policy never refuses the call.
        assertThat(tool.at("/annotations/destructiveHint").asBoolean(true))
                .as(tool.toString())
                .isFalse();
        assertThat(tool.at("/annotations/idempotentHint").asBoolean(false))
                .as(tool.toString())
                .isTrue();
        assertThat(tool.at("/annotations/openWorldHint").asBoolean(true))
                .as(tool.toString())
                .isFalse();
    }

    @ParameterizedTest(name = "{0} id=''{1}''")
    @CsvSource({
        "kb_read, no-such-record",
        "kb_related, no-such-record",
        "kb_read, ''",
        "kb_read, '   '"
    })
    void an_unknown_or_blank_id_answers_error_with_hint(String tool, String id) throws Exception {
        assertErrorWithHint(mcp.text(tool, Map.of("id", id)));
    }

    /**
     * Fulltext query syntax the agent did not mean as syntax: the tool still answers, with results
     * or {@code {error, hint}}, and the parser's failure never reaches the agent.
     */
    @ParameterizedTest(name = "kb_search ''{0}''")
    @ValueSource(strings = {"\"unbalanced", "title:(", "*", "AND", "\\", "?~^", "", "   "})
    void a_hostile_search_query_never_throws(String query) throws Exception {
        assertNoInternalDetail(mcp.text("kb_search", Map.of("query", query)));
    }
}
