package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.MAX_LIMIT;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertCurrentBeforeHistory;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.lineAbout;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultIds;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultLines;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.top;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * kb_search over the seed through the real /mcp endpoint (docs/MCP-TOOLS.md, SPEC §6.3, §6.6,
 * invariant 12). Expected values come from seed/README.md "Bilerek konulan durumlar" and
 * eval/questions.yaml; "top 3" is the eval rule. The budget is in {@link SearchBudgetTest}.
 */
class SearchToolTest extends RunningServerTestBase {

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    /** Eval q16 and the SPEC §6.6 example line, verbatim. */
    @Test
    void an_old_ip_finds_the_server_marked_old_with_the_current_ip() throws Exception {
        String text = mcp.search("192.0.2.20", Map.of());

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        assertThat(resultLines(text))
                .first()
                .isEqualTo(
                        "1. ornek-app-01 · Server — ip: 192.0.2.20 @prod"
                                + " · eski (2026-01-15 → 2026-05-10) · güncel: 192.0.2.21");
    }

    /** Eval q17: archived ornek-app-00 is found through its alias and marked. */
    @Test
    void an_archived_entity_is_found_and_marked() throws Exception {
        String text = mcp.search("app00", Map.of());

        assertThat(lineAbout(text, "ornek-app-00"))
                .as(text)
                .hasValueSatisfying(
                        line -> assertThat(line).contains("ornek-app-00 · Server · arşiv"));
    }

    /** Eval q12, ADR-0018 decision 6: the record says İzmir, only in its summary. */
    @ParameterizedTest
    @ValueSource(strings = {"izmir", "İzmir", "IZMIR", "Izmir"})
    void every_casing_of_izmir_finds_the_backup_server(String query) throws Exception {
        assertThat(top(mcp.search(query, Map.of()))).contains("ornek-yedek-01");
    }

    /**
     * todo.md G6 "Açık": aliases are a LIST<STRING>. Each query below is in an alias only (no id,
     * summary or fact holds it as a token), so only an indexed alias can find it.
     */
    @ParameterizedTest
    @CsvSource({"app00, ornek-app-00", "app01, ornek-app-01", "f5, ornek-lb"})
    void an_alias_finds_its_entity(String alias, String id) throws Exception {
        assertThat(top(mcp.search(alias, Map.of()))).contains(id);
    }

    /** SPEC §6.6 second example: the current record first, the archived one after it, marked. */
    @Test
    void current_results_come_before_archived_ones() throws Exception {
        String text = mcp.search("uygulama sunucusu", Map.of("limit", MAX_LIMIT));

        assertThat(resultLines(text))
                .first()
                .isEqualTo("1. ornek-app-01 · Server — Örnek uygulama sunucusu.");
        assertThat(resultLines(text))
                .last()
                .asString()
                .endsWith(
                        "ornek-app-00 · Server · arşiv — Eski uygulama sunucusu; uygulamalar"
                                + " 2026-05'te ornek-app-01'e taşındı.");
        assertCurrentBeforeHistory(text);
    }

    /**
     * Invariant 12: neither valid_to nor status hides a record. "-k" is query syntax to Lucene (NOT
     * k), yet eval q03 must find the lesson that contains it.
     */
    @ParameterizedTest(name = "{0} finds {1} ({2})")
    @CsvSource({
        "192.0.2.20, ornek-app-01, closed fact",
        "192.0.2.25, ornek-app-01, conflict fact",
        "ORNEKPDB, ornek-db, observed entity",
        "app00, ornek-app-00, archived entity",
        "'TLS hatası curl -k', ders-ornek-tls-yaniltici-test, lesson",
        "'constructor injection spring', karar-global-constructor-injection, decision"
    })
    void nothing_is_hidden_by_time_or_status(String query, String id, String why) throws Exception {
        assertThat(top(mcp.search(query, Map.of()))).as(why).contains(id);
    }

    /** Eval q04: the type filter keeps decisions only. */
    @Test
    void the_type_filter_keeps_only_that_type() throws Exception {
        List<String> ids =
                resultIds(mcp.search("ayrı systemd servisi", Map.of("type", "decision")));

        assertThat(ids).isNotEmpty().contains("karar-ornek-ayri-systemd-servisleri");
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith("karar-"));
    }
}
