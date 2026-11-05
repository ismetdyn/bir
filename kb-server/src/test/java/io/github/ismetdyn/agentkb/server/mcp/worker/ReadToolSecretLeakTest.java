package io.github.ismetdyn.agentkb.server.mcp.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant 2: a secret value lives only in {@code secret_value} and appears in no read tool
 * output. The value is written straight into the fixture graph (the secret endpoint is G8), on
 * every seed Secret, then every read tool is asked about the secrets, their owners and the value
 * itself.
 */
class ReadToolSecretLeakTest extends RunningServerTestBase {

    // The marker is what a leak would show; the prefix only makes the value look real.
    private static final String MARKER = "7f3a9c2e51d84b06";
    private static final String FAKE_VALUE = "kbtest-FAKE-" + MARKER;
    // The seed's secrets; other server tests (kb_scan) add their own to the shared graph.
    private static final List<String> SECRET_IDS =
            List.of(
                    "ornek-app-01/prod/deploy-ssh-key",
                    "ornek-lb/prod/panel-admin",
                    "ornek-proje/prod/spring.datasource.password");

    @Autowired private EmbeddedNeo4jStore store;

    private McpClient mcp;

    @BeforeEach
    void writeFakeSecretValues() throws IOException, InterruptedException {
        Number written =
                (Number)
                        store.database()
                                .executeTransactionally(
                                        "MATCH (s:Secret) WHERE s.id IN $ids"
                                                + " SET s.secret_value = $value RETURN count(s) AS n",
                                        Map.of("ids", SECRET_IDS, "value", FAKE_VALUE),
                                        result -> result.next().get("n"));
        assertThat(written.intValue())
                .as("fixture precondition: every seed secret holds the value")
                .isEqualTo(SECRET_IDS.size());
        mcp = McpClient.open(port);
    }

    @Test
    void no_read_tool_output_contains_a_secret_value() throws Exception {
        List<String> outputs = new ArrayList<>();
        for (String query :
                List.of(FAKE_VALUE, MARKER, "deploy-ssh-key", "panel-admin", "password", "ornek")) {
            outputs.add(mcp.text("kb_search", Map.of("query", query, "limit", 20)));
        }
        List<String> ids = new ArrayList<>(SECRET_IDS);
        ids.addAll(List.of("ornek-app-01", "ornek-lb", "ornek-proje"));
        for (String id : ids) {
            outputs.add(mcp.text("kb_read", Map.of("id", id)));
            outputs.add(mcp.text("kb_related", Map.of("id", id, "direction", "both", "depth", 3)));
        }
        outputs.add(mcp.text("kb_stale", Map.of("days", 1)));
        outputs.add(mcp.text("kb_schema", Map.of()));
        outputs.add(mcp.toolsList().toString());

        // Without this the test passes when secrets are never rendered at all.
        assertThat(String.join("\n", outputs))
                .as("the secret path was exercised")
                .contains("kb:secret/ornek-app-01/prod/deploy-ssh-key");
        assertThat(outputs).noneSatisfy(output -> assertThat(output).contains(MARKER));
    }
}
