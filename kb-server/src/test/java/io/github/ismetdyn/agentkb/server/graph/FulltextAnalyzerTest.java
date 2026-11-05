package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;

/**
 * K6: what each Neo4j fulltext analyzer really returns for Turkish casing. Every analyzer the
 * database lists is measured against real indexes; the table is written to
 * target/f1-analyzer-table.md for the ADR.
 */
class FulltextAnalyzerTest {

    private static final String STANDARD_NO_STOP_WORDS = "standard-no-stop-words";
    private static final String STANDARD_FOLDING = "standard-folding";
    private static final List<String> INDEXED_VALUES =
            List.of("İzmir", "IZMIR", "izmir", "Izmir", "ısı", "ISI", "ISITMA", "İSİ");
    private static final List<String> QUERIES = List.of("izmir", "İzmir", "IZMIR", "ısı", "ISI");
    private static final Path TABLE_FILE = Path.of("target", "f1-analyzer-table.md");
    private static final int INDEX_AWAIT_SECONDS = 120;
    private static final String ERROR_PREFIX = "ERROR: ";

    // analyzer -> query -> rendered cell
    private static final Map<String, Map<String, String>> TABLE = new LinkedHashMap<>();

    @BeforeAll
    static void measureEveryAnalyzer(@TempDir Path dataDir) throws IOException {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir)) {
            GraphDatabaseService database = store.database();
            List<String> analyzers =
                    database.executeTransactionally(
                            "CALL db.index.fulltext.listAvailableAnalyzers() YIELD analyzer RETURN analyzer ORDER BY analyzer",
                            Map.of(),
                            result ->
                                    result.stream()
                                            .map(row -> (String) row.get("analyzer"))
                                            .toList());
            // Neo4j allows one fulltext index per label and property, so each analyzer gets its own
            // label.
            for (int i = 0; i < analyzers.size(); i++) {
                database.executeTransactionally(
                        "UNWIND $values AS value CREATE (:" + labelName(i) + " {text: value})",
                        Map.of("values", INDEXED_VALUES));
                createIndex(database, i, analyzers.get(i));
            }
            database.executeTransactionally(
                    "CALL db.awaitIndexes($seconds)", Map.of("seconds", INDEX_AWAIT_SECONDS));

            for (int i = 0; i < analyzers.size(); i++) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String query : QUERIES) {
                    row.put(query, queryCell(database, indexName(i), query));
                }
                TABLE.put(analyzers.get(i), row);
            }
        }
        Files.writeString(TABLE_FILE, renderTable());
    }

    @Test
    void available_analyzers_list_is_not_empty_and_has_the_candidates() {
        assertThat(TABLE.keySet())
                .isNotEmpty()
                .contains(
                        STANDARD_NO_STOP_WORDS,
                        STANDARD_FOLDING,
                        "standard",
                        "simple",
                        "keyword",
                        "unicode_whitespace",
                        "url_or_email",
                        "turkish");
    }

    @Test
    void table_file_reports_every_analyzer_and_query() throws IOException {
        String table = Files.readString(TABLE_FILE);

        assertThat(table)
                .contains(TABLE.keySet().stream().map(analyzer -> "| " + analyzer + " |").toList());
        assertThat(table).contains(QUERIES);
    }

    @Test
    void measurement_discriminates_keyword_analyzer_matches_exact_value_only() {
        assertThat(TABLE.get("keyword").get("izmir")).isEqualTo("izmir");
    }

    @Test
    void standard_no_stop_words_query_izmir_finds_dotted_capital_izmir() {
        assertThat(cellValues(STANDARD_NO_STOP_WORDS, "izmir")).contains("İzmir");
    }

    @Test
    void current_behavior_standard_no_stop_words_query_dotless_isi_misses_uppercase_ISI() {
        assertThat(cellValues(STANDARD_NO_STOP_WORDS, "ısı")).containsExactly("ısı");
    }

    @Test
    void
            current_behavior_standard_folding_matches_every_case_form_and_merges_dotted_with_dotless() {
        assertThat(cellValues(STANDARD_FOLDING, "izmir"))
                .containsExactly("İzmir", "IZMIR", "izmir", "Izmir");
        assertThat(cellValues(STANDARD_FOLDING, "ısı")).containsExactly("ısı", "ISI", "İSİ");
        assertThat(cellValues(STANDARD_FOLDING, "ISI")).containsExactly("ısı", "ISI", "İSİ");
    }

    private static List<String> cellValues(String analyzer, String query) {
        String cell = TABLE.get(analyzer).get(query);
        assertThat(cell).doesNotStartWith(ERROR_PREFIX);
        return List.of(cell.split(", "));
    }

    private static String indexName(int position) {
        return "analyzer_" + position;
    }

    private static String labelName(int position) {
        return "Doc" + position;
    }

    private static void createIndex(GraphDatabaseService database, int position, String analyzer) {
        // Analyzer names come from the database's own list.
        database.executeTransactionally(
                "CREATE FULLTEXT INDEX "
                        + indexName(position)
                        + " FOR (n:"
                        + labelName(position)
                        + ") ON EACH [n.text] OPTIONS {indexConfig: {`fulltext.analyzer`: '"
                        + analyzer
                        + "'}}");
    }

    private static String queryCell(GraphDatabaseService database, String index, String query) {
        try {
            List<String> found =
                    database.executeTransactionally(
                            "CALL db.index.fulltext.queryNodes($index, $query) YIELD node RETURN node.text AS text",
                            Map.of("index", index, "query", query),
                            result ->
                                    result.stream().map(row -> (String) row.get("text")).toList());
            List<String> ordered = INDEXED_VALUES.stream().filter(found::contains).toList();
            return ordered.isEmpty() ? "-" : String.join(", ", ordered);
        } catch (QueryExecutionException e) {
            return ERROR_PREFIX + e.getMessage().lines().findFirst().orElse("");
        }
    }

    private static String renderTable() {
        StringBuilder out =
                new StringBuilder("| analyzer | ")
                        .append(String.join(" | ", QUERIES))
                        .append(" |\n|---|")
                        .append("---|".repeat(QUERIES.size()))
                        .append('\n');
        TABLE.forEach(
                (analyzer, row) ->
                        out.append("| ")
                                .append(analyzer)
                                .append(" | ")
                                .append(String.join(" | ", row.values()))
                                .append(" |\n"));
        return out.toString();
    }
}
