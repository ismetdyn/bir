package io.github.ismetdyn.agentkb.server.snapshot;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.EDGES;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.NODES;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.SEED_SNAPSHOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The server started with {@code --agentkb.repo} on an empty graph loads {@code <repo>/snapshot}.
 * The repo is a copy so nothing the server writes on shutdown can touch seed/.
 */
class StartupImportTest {

    private static final String ORPHAN_EDGE_ID = "01ORPHANEDGE0000000000STRT";

    @TempDir Path dataDir;
    @TempDir Path repo;

    @Test
    void server_start_on_an_empty_graph_loads_the_repo_snapshot() throws IOException {
        Path snapshot = Files.createDirectories(repo.resolve("snapshot"));
        Files.copy(SEED_SNAPSHOT.resolve(NODES), snapshot.resolve(NODES));
        Files.copy(SEED_SNAPSHOT.resolve(EDGES), snapshot.resolve(EDGES));

        try (ConfigurableApplicationContext context = start()) {
            EmbeddedNeo4jStore store = context.getBean(EmbeddedNeo4jStore.class);

            assertThat(count(store, "MATCH (n) RETURN count(n) AS v"))
                    .isEqualTo(Files.readAllLines(SEED_SNAPSHOT.resolve(NODES)).size());
            assertThat(count(store, "MATCH ()-[r]->() RETURN count(r) AS v"))
                    .isEqualTo(Files.readAllLines(SEED_SNAPSHOT.resolve(EDGES)).size());
        }
    }

    /** A fresh install (no repo, no snapshot) still gets the index kb_search queries. */
    @Test
    void server_start_without_a_repo_creates_the_fulltext_index() {
        try (ConfigurableApplicationContext context =
                new SpringApplication(AgentkbServerApplication.class)
                        .run("--server.port=0", "--agentkb.data-dir=" + dataDir)) {
            EmbeddedNeo4jStore store = context.getBean(EmbeddedNeo4jStore.class);

            assertThat(count(store, "MATCH (n) RETURN count(n) AS v")).isZero();
            assertThat(
                            count(
                                    store,
                                    "SHOW FULLTEXT INDEXES YIELD name, state"
                                            + " WHERE name = 'kb_fulltext' AND state = 'ONLINE'"
                                            + " RETURN count(*) AS v"))
                    .isEqualTo(1);
        }
    }

    @Test
    void an_edge_with_a_missing_endpoint_stops_startup_naming_the_edge() throws IOException {
        Path snapshot = Files.createDirectories(repo.resolve("snapshot"));
        Files.writeString(
                snapshot.resolve(NODES),
                "{\"id\": \"node-a\", \"labels\": [\"Topic\"], \"props\": {}}\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                snapshot.resolve(EDGES),
                "{\"from\": \"node-a\", \"id\": \""
                        + ORPHAN_EDGE_ID
                        + "\", \"props\": {}, \"to\": \"ghost-node\", \"type\": \"ABOUT\"}\n",
                StandardCharsets.UTF_8);

        Throwable failure = catchThrowable(() -> start().close());

        assertThat(failure).as("the server started instead of refusing").isNotNull();
        assertThat(messagesOf(failure)).contains(ORPHAN_EDGE_ID);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplication(AgentkbServerApplication.class)
                .run("--server.port=0", "--agentkb.data-dir=" + dataDir, "--agentkb.repo=" + repo);
    }

    private static int count(EmbeddedNeo4jStore store, String cypher) {
        return ((Number)
                        store.database()
                                .executeTransactionally(
                                        cypher, Map.of(), result -> result.next().get("v")))
                .intValue();
    }

    private static String messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null && messages.size() < 20; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return String.join(" | ", messages);
    }
}
