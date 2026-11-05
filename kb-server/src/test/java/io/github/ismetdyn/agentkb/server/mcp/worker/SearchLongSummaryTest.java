package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.MAX_LIMIT;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.lineAbout;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.moreCount;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultIds;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Invariants 11 and 12: the import keeps a summary longer than the 300-character write limit (SPEC
 * §5 "Metin sınırları"), and kb_search must still reach every hit. One over-long line may not use
 * up the 8,000-character budget and hide the results around it; its snippet is cut to the summary
 * limit and marked with an ellipsis.
 */
class SearchLongSummaryTest {

    private static final Path SEED = Path.of("..", "seed");
    private static final String TERM = "zebrakuyruk";
    private static final String LONG_ID = "uzun-ozet-01";
    private static final List<String> SHORT_IDS = List.of("kisa-ozet-01", "kisa-ozet-02");
    private static final int LONG_SUMMARY_CHARS = 9_000;
    private static final int SUMMARY_LIMIT = 300;
    private static final String ELLIPSIS = "…";
    private static final String SNIPPET_DASH = " — ";
    private static final String LONG_SUMMARY_START = "Uzun özet " + TERM + " ";

    @TempDir Path dataDir;
    @TempDir Path repo;

    @Test
    void an_over_long_summary_is_cut_and_the_other_hits_stay_visible() throws Exception {
        seedCopyWithLongSummary();

        try (ConfigurableApplicationContext context = start()) {
            assertThat(storedSummaryLength(context))
                    .as("precondition: the import kept the whole summary")
                    .isEqualTo(LONG_SUMMARY_CHARS);
            McpClient mcp = McpClient.open(port(context));

            String text = mcp.search(TERM, Map.of("limit", MAX_LIMIT));

            assertThat(resultIds(text))
                    .as(text)
                    .containsExactlyInAnyOrder(LONG_ID, SHORT_IDS.get(0), SHORT_IDS.get(1));
            assertThat(moreCount(text)).as(text).isZero();
            String snippet = lineAbout(text, LONG_ID).orElseThrow().split(SNIPPET_DASH, 2)[1];
            assertThat(snippet)
                    .as("the long snippet, cut to the summary limit")
                    .startsWith(LONG_SUMMARY_START)
                    .endsWith(ELLIPSIS)
                    .hasSizeLessThanOrEqualTo(SUMMARY_LIMIT + ELLIPSIS.length());
        }
    }

    private void seedCopyWithLongSummary() throws IOException {
        Path snapshot = Files.createDirectories(repo.resolve("snapshot"));
        Files.copy(SEED.resolve("snapshot/nodes.jsonl"), snapshot.resolve("nodes.jsonl"));
        Files.copy(SEED.resolve("snapshot/edges.jsonl"), snapshot.resolve("edges.jsonl"));
        Path schema = Files.createDirectories(repo.resolve("schema"));
        for (String file : List.of("types.yaml", "relations.yaml", "facts.yaml")) {
            Files.copy(SEED.resolve("schema").resolve(file), schema.resolve(file));
        }
        StringBuilder rows = new StringBuilder(entity(LONG_ID, longSummary()));
        for (String id : SHORT_IDS) {
            rows.append(entity(id, "Kısa özet " + TERM + "."));
        }
        Files.writeString(
                snapshot.resolve("nodes.jsonl"),
                rows,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static String longSummary() {
        StringBuilder summary = new StringBuilder(LONG_SUMMARY_START);
        while (summary.length() < LONG_SUMMARY_CHARS) {
            summary.append("dolgu ");
        }
        return summary.substring(0, LONG_SUMMARY_CHARS);
    }

    private static String entity(String id, String summary) {
        return "{\"id\": \"%s\", \"labels\": [\"Entity\", \"Server\"], \"props\": {\"status\":"
                        .formatted(id)
                + " \"current\", \"summary\": \"%s\", \"updated_at\": \"2026-10-01T09:00:00Z\"}}\n"
                        .formatted(summary);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplication(AgentkbServerApplication.class)
                .run("--server.port=0", "--agentkb.data-dir=" + dataDir, "--agentkb.repo=" + repo);
    }

    private static long storedSummaryLength(ConfigurableApplicationContext context) {
        return (Long)
                context.getBean(EmbeddedNeo4jStore.class)
                        .database()
                        .executeTransactionally(
                                "MATCH (e:Entity {id: $id}) RETURN size(e.summary) AS n",
                                Map.of("id", LONG_ID),
                                result -> result.next().get("n"));
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }
}
