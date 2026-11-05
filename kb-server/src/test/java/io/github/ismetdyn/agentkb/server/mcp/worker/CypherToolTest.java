package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * kb_cypher through the real /mcp endpoint (docs/MCP-TOOLS.md, SPEC §13.5): rows come back as a
 * Markdown table under the data header, at most 200 rows and 8,000 characters, within 5 s; refused
 * or broken queries answer {@code {error, hint}}; no answer ever holds a secret value.
 */
class CypherToolTest extends RunningServerTestBase {

    private static final String MARKER = "5be08d1f7a2c3946";
    private static final String FAKE_VALUE = "kbtest-FAKE-" + MARKER;
    private static final int MAX_ROWS = 200;
    // SPEC §13.5 timeout is 5 s; the rest is start-up of the call and a slow machine.
    private static final Duration ANSWER_WITHIN = Duration.ofSeconds(15);
    // Eval q14, verbatim.
    private static final String Q14 =
            "MATCH (sf:SourceFile {path:'src/main/resources/application-prod.yml'})<-[:FROM]-(f:Fact)"
                    + "<-[:HAS_FACT]-(e:Entity) WHERE f.valid_to IS NULL RETURN DISTINCT e.id";

    @Autowired private EmbeddedNeo4jStore store;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void rows_come_back_as_a_markdown_table_under_the_data_header() throws Exception {
        String text = cypher(Q14);

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        List<List<String>> table = MarkdownTable.cells(text);
        assertThat(table).as(text).isNotEmpty();
        assertThat(table.getFirst()).as("header").containsExactly("e.id");
        assertThat(table.subList(1, table.size()))
                .as(text)
                .extracting(List::getFirst)
                .containsExactlyInAnyOrder("ornek-db", "ornek-app-01", "ornek-proje");
    }

    @Test
    void more_than_200_rows_are_cut_to_200() throws Exception {
        List<List<String>> table =
                MarkdownTable.cells(cypher("UNWIND range(1, 500) AS i RETURN i"));

        assertThat(table.subList(1, table.size())).hasSize(MAX_ROWS);
        assertThat(table).noneSatisfy(row -> assertThat(row).contains("201"));
    }

    @Test
    void a_wide_answer_stays_within_8000_characters_and_says_it_was_cut() throws Exception {
        String text =
                cypher(
                        "UNWIND range(1, 200) AS i"
                                + " RETURN i, reduce(s = '', x IN range(1, 100) | s + 'x') AS pad");

        assertThat(text.length()).isLessThanOrEqualTo(Answers.MAX_CHARS);
        assertThat(text).contains("kısaltıldı");
    }

    @Test
    void a_query_over_the_timeout_answers_error_with_hint() throws Exception {
        long started = System.nanoTime();

        String text =
                cypher(
                        "UNWIND range(1, 100000) AS a UNWIND range(1, 100000) AS b"
                                + " RETURN count(*) AS n");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(ANSWER_WITHIN);
        assertErrorWithHint(text);
    }

    /** The four refusals the G6 acceptance names, and a broken query; none echoes the query. */
    @ParameterizedTest(name = "kb_cypher {0}")
    @ValueSource(
            strings = {
                "CREATE (n:Entity {id: 'guard-miss'}) RETURN n.id",
                "LOAD CSV FROM 'file:///etc/hostname' AS row RETURN row",
                "MATCH (s:Secret) RETURN s.secret_value",
                "MATCH (a:Entity)-[:KNOWS]->(b) RETURN b.id",
                "CALL dbms.components() YIELD name RETURN name",
                "MATCH (n RETURN n",
                "",
                "   "
            })
    void a_refused_or_broken_query_answers_error_with_hint(String query) throws Exception {
        assertErrorWithHint(cypher(query));
        Object written =
                store.read(
                        tx ->
                                tx.execute("MATCH (n {id: 'guard-miss'}) RETURN count(n) AS n")
                                        .next()
                                        .get("n"));
        assertThat(written).isEqualTo(0L);
    }

    /** security.md "Her yeni giriş noktasının limiti var"; kb_search stops at 500 characters. */
    @Test
    void an_oversized_query_answers_error_with_hint() throws Exception {
        String padding = " ".repeat(100_000);

        assertErrorWithHint(cypher("MATCH (e:Entity)" + padding + "RETURN e.id"));
    }

    /**
     * Invariant 2 end to end: every way into the value, refused or filtered, and the value is in no
     * answer. The first query proves the secrets are reachable at all.
     */
    @Test
    void no_answer_holds_a_secret_value() throws Exception {
        writeFakeSecretValues();
        List<String> outputs = new ArrayList<>();
        for (String query :
                List.of(
                        "MATCH (e:Entity)-[:HAS_SECRET]->(s:Secret) RETURN e.id, s.id, s.name",
                        "MATCH (s:Secret) RETURN s",
                        "MATCH (s:Secret) RETURN properties(s)",
                        "MATCH (s:Secret) RETURN s {.*}",
                        "MATCH (s:Secret) RETURN keys(s)",
                        "MATCH (s:Secret) RETURN s.secret_value",
                        "MATCH (s:Secret) RETURN s.secret_value AS x",
                        "MATCH (s:Secret) RETURN s['secret' + '_value'] AS x",
                        "MATCH (s:Secret) RETURN s['\\u0073ecret_value'] AS x",
                        "MATCH (s:Secret) UNWIND keys(s) AS k RETURN k, s[k]",
                        "MATCH p = (:Entity)-[:HAS_SECRET]->(:Secret) RETURN p",
                        "MATCH (e:Entity)-[:HAS_SECRET]->(s) RETURN e.id, collect(s)",
                        "MATCH (s:Secret) WHERE s['secret' + '_value'] STARTS WITH 'kbtest'"
                                + " RETURN s.id")) {
            outputs.add(cypher(query));
        }

        assertThat(outputs.getFirst())
                .as("the secret path was exercised")
                .contains("ornek-lb/prod/panel-admin");
        assertThat(outputs).noneSatisfy(output -> assertThat(output).contains(MARKER));
        // The WHERE oracle answers with ids only; a refusal is the only way it reveals nothing.
        assertErrorWithHint(outputs.getLast());
    }

    private String cypher(String query) throws IOException, InterruptedException {
        return mcp.text("kb_cypher", Map.of("query", query));
    }

    private void writeFakeSecretValues() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret) SET s.secret_value = $value",
                        Map.of("value", FAKE_VALUE));
    }
}
