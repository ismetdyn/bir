package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.MAX_LIMIT;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.moreCount;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultIds;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultLines;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0017 decision 4 and SPEC §6.3: the result budget is applied after ordering, and what it cuts
 * is counted as {@code (+N sonuç daha)}, never silently dropped.
 */
class SearchBudgetTest extends RunningServerTestBase {

    private static final int DEFAULT_LIMIT = 8;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    /**
     * The archived record scores highest here (its summary holds all three words), so cutting
     * before ordering would show it alone.
     */
    @Test
    void the_budget_is_applied_after_ordering() throws Exception {
        String query = "eski uygulama sunucusu";
        int total = resultLines(mcp.search(query, Map.of("limit", MAX_LIMIT))).size();

        String cut = mcp.search(query, Map.of("limit", 1));

        assertThat(resultIds(cut)).containsExactly("ornek-app-01");
        assertThat(moreCount(cut)).as(cut).isEqualTo(total - 1);
    }

    /** MCP-TOOLS kb_search: default limit 8. */
    @Test
    void the_default_limit_is_eight_and_the_rest_is_counted() throws Exception {
        int total = resultLines(mcp.search("ornek", Map.of("limit", MAX_LIMIT))).size();
        assertThat(total).as("seed precondition").isGreaterThan(DEFAULT_LIMIT);

        String cut = mcp.search("ornek", Map.of());

        assertThat(resultLines(cut)).hasSize(DEFAULT_LIMIT);
        assertThat(moreCount(cut)).as(cut).isEqualTo(total - DEFAULT_LIMIT);
    }
}
