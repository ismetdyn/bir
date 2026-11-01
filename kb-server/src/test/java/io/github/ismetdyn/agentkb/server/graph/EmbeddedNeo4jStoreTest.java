package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;

/** K3: durability across restarts and exclusive ownership of the data directory. */
class EmbeddedNeo4jStoreTest {

    // Neo4j words the failure differently within and across processes; both name this lock file.
    private static final String LOCK_ERROR = "store_lock";
    private static final int EXIT_OPEN_REJECTED = 3;
    private static final int EXIT_OPEN_SUCCEEDED = 4;
    private static final long CHILD_TIMEOUT_SECONDS = 120;

    @TempDir
    Path dataDir;

    @Test
    void node_survives_close_and_reopen_on_same_directory() {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir)) {
            createNote(store, "kept");
        }

        try (EmbeddedNeo4jStore reopened = new EmbeddedNeo4jStore(dataDir)) {
            assertThat(noteNames(reopened)).containsExactly("kept");
        }
    }

    @Test
    void graph_files_live_under_data_dir_graph_and_other_directory_stays_empty(@TempDir Path otherDir) {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir)) {
            createNote(store, "kept");
        }

        assertThat(dataDir.resolve("graph")).isDirectory();
        try (EmbeddedNeo4jStore unrelated = new EmbeddedNeo4jStore(otherDir)) {
            assertThat(noteNames(unrelated)).isEmpty();
        }
    }

    @Test
    void closed_store_stops_serving_its_database() {
        EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir);
        GraphDatabaseService database = store.database();
        assertThat(database.isAvailable()).isTrue();

        store.close();

        assertThat(database.isAvailable()).isFalse();
    }

    @Test
    void second_open_in_same_process_fails_on_lock_and_lock_is_released_on_close() {
        try (EmbeddedNeo4jStore first = new EmbeddedNeo4jStore(dataDir)) {
            createNote(first, "before");

            assertThatThrownBy(this::openAndClose).as("second open").hasStackTraceContaining(LOCK_ERROR);
            // A failed attempt must not drop the lock held by the first store.
            assertThatThrownBy(this::openAndClose).as("third open").hasStackTraceContaining(LOCK_ERROR);

            createNote(first, "after");
        }

        try (EmbeddedNeo4jStore reopened = new EmbeddedNeo4jStore(dataDir)) {
            assertThat(noteNames(reopened)).containsExactlyInAnyOrder("before", "after");
        }
    }

    @Test
    void second_open_from_another_process_fails_while_first_is_open() throws IOException, InterruptedException {
        try (EmbeddedNeo4jStore first = new EmbeddedNeo4jStore(dataDir)) {
            createNote(first, "before");

            Path output = Files.createTempFile("agentkb-child", ".log");
            Process child = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            OpenStoreProbe.class.getName(),
                            dataDir.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            try {
                assertThat(child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                String childLog = Files.readString(output);
                assertThat(child.exitValue()).as(childLog).isEqualTo(EXIT_OPEN_REJECTED);
                assertThat(childLog).contains(LOCK_ERROR);
                assertThat(noteNames(first)).containsExactly("before");
            } finally {
                child.destroyForcibly();
                Files.deleteIfExists(output);
            }
        }
    }

    private void openAndClose() {
        new EmbeddedNeo4jStore(dataDir).close();
    }

    private static void createNote(EmbeddedNeo4jStore store, String name) {
        store.database().executeTransactionally("CREATE (:Note {name: $name})", Map.of("name", name));
    }

    private static List<String> noteNames(EmbeddedNeo4jStore store) {
        return store.database()
                .executeTransactionally(
                        "MATCH (n:Note) RETURN n.name AS name",
                        Map.of(),
                        result -> result.stream().map(row -> (String) row.get("name")).toList());
    }

    /** Runs in a child JVM. A distinct exit code keeps a classpath or startup crash (exit 1) from passing the test. */
    static final class OpenStoreProbe {

        public static void main(String[] args) {
            int exitCode;
            try (EmbeddedNeo4jStore ignored = new EmbeddedNeo4jStore(Path.of(args[0]))) {
                exitCode = EXIT_OPEN_SUCCEEDED;
            } catch (RuntimeException e) {
                e.printStackTrace();
                exitCode = EXIT_OPEN_REJECTED;
            }
            System.exit(exitCode);
        }
    }
}
