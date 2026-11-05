package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertNoInternalDetail;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.section;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * kb_remember over the real /mcp endpoint, with the input of docs/MCP-TOOLS.md. Source mapping is
 * SPEC §7 rules 3 and 7: user_stated only when the evidence is in the PromptRing, anything else is
 * agent and unverified. Every test writes its own zz-g7-* entity so that the shared seed graph of
 * the other server tests keeps its records.
 */
class RememberToolTest extends RunningServerTestBase {

    private static final String FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) RETURN f.value AS value,"
                    + " f.source AS source, f.status AS status, f.evidence AS evidence";

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private PromptRing promptRing;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void the_agent_endpoint_offers_kb_remember_and_kb_propose() throws Exception {
        List<String> names = new ArrayList<>();
        mcp.toolsList().at("/result/tools").forEach(tool -> names.add(tool.get("name").asString()));

        assertThat(names).contains("kb_remember", "kb_propose");
    }

    @Test
    void a_finding_from_code_is_stored_as_unverified_agent_knowledge() throws Exception {
        String reply =
                remember(
                        "zz-g7-code",
                        "zz-g7-code.ornek.example",
                        Map.of("kind", "code", "ref", "src/main/resources/application.yml"));

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertNoInternalDetail(reply);
        assertThat(facts("zz-g7-code"))
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.get("source")).isEqualTo("agent");
                            assertThat(f.get("status")).isEqualTo("unverified");
                        });
        assertThat(section(mcp.text("kb_read", Map.of("id", "zz-g7-code")), "Bilgiler"))
                .anyMatch(
                        line -> line.contains("hostname: zz-g7-code.ornek.example @prod · agent,"));
    }

    @Test
    void user_stated_with_the_users_own_recent_words_is_stored_as_user_stated() throws Exception {
        String words = "zz-g7-heard sunucusunun adı zz-g7-heard.ornek.example";
        promptRing.record("Bilgi: " + words + ", prod ortamında.", Instant.now());

        remember(
                "zz-g7-heard",
                "zz-g7-heard.ornek.example",
                Map.of("kind", "user_stated", "evidence", words));

        assertThat(facts("zz-g7-heard"))
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.get("source")).isEqualTo("user_stated");
                            assertThat(f.get("status")).isEqualTo("ok");
                            assertThat(f.get("evidence")).isEqualTo(words);
                        });
    }

    @Test
    void user_stated_with_words_the_user_never_wrote_is_stored_as_agent() throws Exception {
        remember(
                "zz-g7-unheard",
                "zz-g7-unheard.ornek.example",
                Map.of("kind", "user_stated", "evidence", "zz-g7-unheard is the host, I said so"));

        assertThat(facts("zz-g7-unheard"))
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.get("source")).isEqualTo("agent");
                            assertThat(f.get("status")).isEqualTo("unverified");
                        });
    }

    /**
     * MCP-TOOLS: a contradicting fact does not overwrite verified knowledge; the result says so.
     */
    @Test
    void a_fact_against_verified_knowledge_is_kept_as_a_conflict_and_reported() throws Exception {
        String words = "zz-g7-disputed adı zz-g7-disputed.ornek.example";
        promptRing.record(words, Instant.now());
        remember(
                "zz-g7-disputed",
                "zz-g7-disputed.ornek.example",
                Map.of("kind", "user_stated", "evidence", words));

        String reply =
                remember(
                        "zz-g7-disputed",
                        "zz-g7-elsewhere.ornek.example",
                        Map.of("kind", "doc", "ref", "docs/runbook.md"));

        assertThat(Ids.normalize(reply)).containsAnyOf("conflict", "çelişki");
        assertThat(section(mcp.text("kb_read", Map.of("id", "zz-g7-disputed")), "Bilgiler"))
                .anyMatch(
                        line ->
                                line.contains("zz-g7-elsewhere.ornek.example")
                                        && line.contains(
                                                "ÇELİŞKİ (zz-g7-disputed.ornek.example korunuyor)"));
    }

    private String remember(String entity, String hostname, Map<String, String> source)
            throws IOException, InterruptedException {
        return mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        entity,
                        "type",
                        "server",
                        "facts",
                        List.of(Map.of("key", "hostname", "value", hostname, "env", "prod")),
                        "source",
                        source));
    }

    private List<Map<String, Object>> facts(String entity) {
        return store.read(tx -> tx.execute(FACTS, Map.of("id", entity)).stream().toList());
    }
}
