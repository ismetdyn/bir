package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * kb_propose (docs/MCP-TOOLS.md) and SPEC §7 rule 6: an agent never writes a normative change
 * itself. It opens a Proposal linked to the record it changes with ABOUT, and nothing else changes
 * until the user accepts it.
 */
class ProposeToolTest extends RunningServerTestBase {

    private static final String TARGET = "zz-g7-propose-target";
    private static final String PROPOSAL =
            "MATCH (p:Proposal {content: $content}) OPTIONAL MATCH (p)-[:ABOUT]->(x)"
                    + " RETURN properties(p) AS p, collect(x.id) AS about";
    private static final String COUNT = "MATCH (n:%s) RETURN count(n) AS n";

    @Autowired private EmbeddedNeo4jStore store;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
        mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        TARGET,
                        "type",
                        "server",
                        "facts",
                        List.of(Map.of("key", "os", "value", "rhel 9", "env", "prod")),
                        "source",
                        Map.of("kind", "code")));
    }

    @Test
    void an_archive_proposal_is_about_its_target_and_does_not_archive_it() throws Exception {
        Object statusBefore = targetStatus();
        String content = "Arşivle: zz-g7 hedef sunucu kullanılmıyor.";

        String reply =
                mcp.text(
                        "kb_propose",
                        Map.of(
                                "kind",
                                "archive",
                                "target",
                                TARGET,
                                "content",
                                content,
                                "evidence",
                                "No project uses it since 2026-10."));

        assertThat(reply).doesNotStartWith("{\"error\"");
        List<Map<String, Object>> rows = rows(PROPOSAL, Map.of("content", content));
        assertThat(rows).hasSize(1);
        Map<?, ?> proposal = (Map<?, ?>) rows.getFirst().get("p");
        assertThat((String) proposal.get("id")).matches("[0-9A-HJKMNP-TV-Z]{26}");
        // ADR-0022 decision 31 (f): no reason field, the why is in evidence; status starts open.
        assertThat(proposal)
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("kind", "archive")
                .containsEntry("by", "agent")
                .containsEntry("status", "open")
                .containsEntry("evidence", "No project uses it since 2026-10.")
                .doesNotContainKey("reason");
        assertThat(rows.getFirst().get("about")).isEqualTo(List.of(TARGET));
        assertThat(targetStatus()).as("the target is not archived").isEqualTo(statusBefore);
    }

    @Test
    void a_decision_proposal_writes_a_proposal_and_no_decision() throws Exception {
        long decisions = count("Decision");
        String content = "Kural: zz-g7 servisleri ayrı systemd biriminde çalışır.";

        mcp.text(
                "kb_propose",
                Map.of(
                        "kind", "decision",
                        "content", content,
                        "evidence", "Seen in three projects."));

        assertThat(rows(PROPOSAL, Map.of("content", content))).hasSize(1);
        assertThat(count("Decision")).isEqualTo(decisions);
    }

    /** Decision 31 (f): content alone is a whole proposal; evidence is optional. */
    @Test
    void a_proposal_without_evidence_is_open() throws Exception {
        String content = "Kural: zz-g7 kanıtsız öneri de açılır.";

        String reply = mcp.text("kb_propose", Map.of("kind", "decision", "content", content));

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertThat(rows(PROPOSAL, Map.of("content", content)))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.get("p"))
                                        .asInstanceOf(
                                                InstanceOfAssertFactories.map(
                                                        String.class, Object.class))
                                        .containsEntry("status", "open")
                                        .doesNotContainKey("reason"));
    }

    /** Decision 31 (f): the tool announces no reason argument to the agent. */
    @Test
    void kb_propose_offers_no_reason_argument() throws Exception {
        JsonNode schema = null;
        for (JsonNode tool : mcp.toolsList().at("/result/tools")) {
            if ("kb_propose".equals(tool.path("name").asString())) {
                schema = tool.path("inputSchema");
            }
        }

        assertThat(schema).as("kb_propose is listed").isNotNull();
        assertThat(schema.path("properties").has("reason")).isFalse();
        assertThat(schema.path("properties").has("evidence")).isTrue();
        assertThat(schema.path("required").toString()).doesNotContain("reason");
    }

    @ParameterizedTest(name = "kind={0} target={1} content={2}")
    @CsvSource(
            nullValues = "null",
            value = {
                "delete-everything, zz-g7-propose-target, zz-g7 bad kind",
                "archive, zz-g7-no-such-record, zz-g7 bad target",
                "decision, null, ''"
            })
    void a_bad_proposal_answers_error_and_hint_and_writes_nothing(
            String kind, String target, String content) throws Exception {
        long proposals = count("Proposal");
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("kind", kind);
        if (target != null) {
            arguments.put("target", target);
        }
        arguments.put("content", content);
        arguments.put("evidence", "zz-g7 test");

        assertErrorWithHint(mcp.text("kb_propose", arguments));
        assertThat(count("Proposal")).isEqualTo(proposals);
    }

    private Object targetStatus() {
        return rows("MATCH (e:Entity {id: $id}) RETURN e.status AS s", Map.of("id", TARGET))
                .getFirst()
                .get("s");
    }

    private long count(String label) {
        return (Long) rows(COUNT.formatted(label), Map.of()).getFirst().get("n");
    }

    private List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(tx -> tx.execute(cypher, params).stream().toList());
    }
}
