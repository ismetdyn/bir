package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar agentkb-server.jar init} (docs/CLIENTS.md §2, ARCHITECTURE §8, ADR-0022 decision
 * 3): in an empty home it creates the data directory 0700 and {@code ~/kb} as a git repository
 * holding {@code schema/} from seed/schema and an empty snapshot, committed, without opening Neo4j.
 * A second run changes nothing and exits 0 with a message. A {@code ~/kb} that is not a git
 * repository is left alone with an error. The server then starts on what init left (plan.md G14).
 *
 * <p>Runs the real main class in its own JVM with HOME and {@code user.home} on a temporary
 * directory, no user git identity (GIT_CONFIG_NOSYSTEM, empty home) and no XDG, AGENTKB or SPRING
 * variables, so nothing of this machine's home is read or written. SERVER_PORT=0 only keeps a
 * mistaken server start off the real port.
 */
class InitCommandTest {

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");
    private static final List<String> SNAPSHOT_FILES =
            List.of("snapshot/nodes.jsonl", "snapshot/edges.jsonl");
    private static final long TIMEOUT_SECONDS = 30;

    @TempDir Path home;

    private record Run(int exit, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }

    @Test
    void init_in_an_empty_home_creates_the_data_directory_and_the_knowledge_repository()
            throws IOException {
        Run run = init();

        assertThat(run.exit()).as(run.output()).isZero();
        Path dataDir = home.resolve(".local/share/agentkb");
        assertThat(Files.getPosixFilePermissions(dataDir))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        Path kb = home.resolve("kb");
        for (String file : SCHEMA_FILES) {
            assertThat(kb.resolve("schema").resolve(file))
                    .as("schema/%s from seed/schema", file)
                    .hasSameBinaryContentAs(SEED_SCHEMA.resolve(file));
        }
        for (String file : SNAPSHOT_FILES) {
            assertThat(kb.resolve(file)).as("empty %s", file).isEmptyFile();
        }
        assertThat(ScanProjects.git(kb, "ls-tree", "-r", "--name-only", "HEAD").lines())
                .as("committed without a user git identity")
                .containsAll(SNAPSHOT_FILES)
                .contains("schema/types.yaml", "schema/relations.yaml", "schema/facts.yaml")
                .as("ADR-0027 decision 8: LF snapshot, byte-exact blobs on any clone")
                .contains(".gitattributes");
        assertThat(kb.resolve(".gitattributes"))
                .hasContent("snapshot/*.jsonl text eol=lf\nblobs/** -text\n");
        assertThat(ScanProjects.git(kb, "status", "--porcelain")).as("nothing left over").isEmpty();
    }

    /** ADR-0022 decision 3: init runs without Neo4j, so the data directory holds no file. */
    @Test
    void init_does_not_open_the_graph() throws IOException {
        Run run = init();

        assertThat(run.exit()).as(run.output()).isZero();
        try (Stream<Path> files = Files.walk(home.resolve(".local/share/agentkb"))) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .as("files Neo4j would create (store_lock, graph/)")
                    .isEmpty();
        }
    }

    @Test
    void a_second_init_changes_nothing_and_keeps_existing_data() throws IOException {
        assertThat(init().exit()).isZero();
        Path kb = home.resolve("kb");
        // The user's data since the first init: a committed snapshot, an untracked file, a graph.
        Files.writeString(
                kb.resolve("snapshot/nodes.jsonl"),
                "{\"id\": \"zz-init-host\", \"labels\": [\"Entity\"], \"props\": {}}\n",
                StandardCharsets.UTF_8);
        ScanProjects.commitAll(kb, "user data");
        Files.writeString(kb.resolve("notes.txt"), "mine\n", StandardCharsets.UTF_8);
        Path graphFile =
                Files.createDirectories(home.resolve(".local/share/agentkb/graph"))
                        .resolve("marker");
        Files.writeString(graphFile, "graph data\n", StandardCharsets.UTF_8);
        String head = ScanProjects.git(kb, "rev-parse", "HEAD");
        Map<Path, String> before = contents(home);

        Run second = init();

        assertThat(second.exit()).as(second.output()).isZero();
        assertThat(second.output()).as("a message saying what it found").isNotBlank();
        assertThat(ScanProjects.git(kb, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(contents(home)).isEqualTo(before);
    }

    @Test
    void a_kb_directory_that_is_no_git_repository_is_left_alone_with_an_error() throws IOException {
        Path kb = Files.createDirectories(home.resolve("kb"));
        Files.writeString(kb.resolve("mine.txt"), "the user's own files\n", StandardCharsets.UTF_8);
        Map<Path, String> before = contents(home.resolve("kb"));

        Run run = init();

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.stderr()).as("a message on stderr").isNotBlank();
        assertThat(kb.resolve(".git")).doesNotExist();
        assertThat(contents(kb)).isEqualTo(before);
    }

    /** plan.md G14: init, then the service starts on what init left, with an empty graph. */
    @Test
    void the_server_starts_on_what_init_left() {
        assertThat(init().exit()).isZero();

        try (ConfigurableApplicationContext context =
                new SpringApplication(AgentkbServerApplication.class)
                        .run(
                                "--server.port=0",
                                "--agentkb.data-dir=" + home.resolve(".local/share/agentkb"),
                                "--agentkb.repo=" + home.resolve("kb"))) {
            EmbeddedNeo4jStore store = context.getBean(EmbeddedNeo4jStore.class);

            long records =
                    store.read(
                            tx ->
                                    (Long)
                                            tx.execute(
                                                            "MATCH (n) WHERE NOT n:Signal"
                                                                    + " RETURN count(n) AS n")
                                                    .next()
                                                    .get("n"));
            assertThat(records).isZero();
        }
    }

    private Run init() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AgentkbServerApplication.class.getName());
        command.add("init");
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile());
        Map<String, String> env = builder.environment();
        env.keySet()
                .removeIf(
                        name ->
                                name.startsWith("XDG_")
                                        || name.startsWith("AGENTKB_")
                                        || name.startsWith("SPRING_")
                                        || name.startsWith("GIT_"));
        env.put("HOME", home.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("SERVER_PORT", "0");
        try {
            Process process = builder.start();
            CompletableFuture<String> out = drain(process.getInputStream());
            CompletableFuture<String> err = drain(process.getErrorStream());
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError(
                        "init did not exit within " + TIMEOUT_SECONDS + " s: " + out.getNow(""));
            }
            return new Run(process.exitValue(), out.join(), err.join());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** Every file outside .git with its content, so a change anywhere shows. */
    private static Map<Path, String> contents(Path root) {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(file);
                if (!relative.startsWith(".git") && !relative.toString().contains("/.git/")) {
                    files.put(relative, Files.readString(file, StandardCharsets.ISO_8859_1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (stream) {
                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
