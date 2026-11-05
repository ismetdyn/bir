package io.github.ismetdyn.agentkb.server;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real application on a real socket. Only the port, the data directory and {@code agentkb.repo}
 * differ from production, so {@code server.address} still comes from application.yml. The repo is a
 * copy of seed/, so the startup import loads the golden data (seed/README.md) and nothing the
 * server writes can touch seed/. The scanner may read only under {@link #scanRoot()}, with
 * ornek.example as internal domain (fixtures/scanner/expected.yaml).
 *
 * <p>All subclasses share this one cached context on purpose. A second web context in the JVM gets
 * the Tomcat engine name {@code Tomcat-1}, and the container loggers masked by name in
 * logback-spring.xml ({@code [Tomcat].[localhost]…}) would then log unmasked
 * (ContainerLogMaskingTest fails in that order).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class RunningServerTestBase {

    private static final Path SEED_SNAPSHOT = Path.of("..", "seed", "snapshot");
    private static final List<String> SNAPSHOT_FILES = List.of("nodes.jsonl", "edges.jsonl");
    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");
    // ponytail: lives under target/ because the cached context outlives any test class; `clean`
    // removes it.
    private static final Path WORK_DIR = createWorkDir();

    @LocalServerPort protected int port;

    @DynamicPropertySource
    static void useTemporaryDirectories(DynamicPropertyRegistry registry) {
        registry.add("agentkb.data-dir", () -> WORK_DIR.resolve("data").toString());
        registry.add("agentkb.repo", () -> WORK_DIR.resolve("repo").toString());
        registry.add("agentkb.scan.allowed-roots", () -> scanRoot().toString());
        registry.add("agentkb.scan.internal-domains", () -> "ornek.example");
    }

    /**
     * {@code agentkb.repo} as kb init leaves it: a git repository whose HEAD holds the seed
     * snapshot, for kb restore and kb check. Made on first use, so tests that never restore do not
     * depend on git; synchronized because the context, and so the repo, is shared.
     */
    protected static synchronized Path committedRepo() {
        Path repo = WORK_DIR.resolve("repo");
        if (!Files.isDirectory(repo.resolve(".git"))) {
            ScanProjects.git(repo, "init", "--quiet");
            ScanProjects.commitAll(repo, "seed snapshot");
        }
        return repo;
    }

    /** The one allowed root of the scanner; its sibling directories are outside it. */
    protected static Path scanRoot() {
        return WORK_DIR.resolve("scan-roots");
    }

    private static Path createWorkDir() {
        try {
            Path target = Files.createDirectories(Path.of("target"));
            Path work = Files.createTempDirectory(target, "agentkb-test-").toAbsolutePath();
            Files.createDirectories(work.resolve("scan-roots"));
            Path snapshot = Files.createDirectories(work.resolve("repo").resolve("snapshot"));
            for (String file : SNAPSHOT_FILES) {
                Files.copy(SEED_SNAPSHOT.resolve(file), snapshot.resolve(file));
            }
            // kb init copies seed/schema into the repo; kb_schema and CypherGuard read it there.
            Path schema = Files.createDirectories(work.resolve("repo").resolve("schema"));
            for (String file : SCHEMA_FILES) {
                Files.copy(SEED_SCHEMA.resolve(file), schema.resolve(file));
            }
            return work;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
