package io.github.ismetdyn.agentkb.server.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The knowledge repository {@code init} creates (docs/CLIENTS.md §2, ARCHITECTURE §8): a git
 * repository on branch main holding {@code schema/} from seed/schema and an empty snapshot, in one
 * commit by the server's identity, so it needs no git identity of the user. Built in a sibling
 * directory and renamed into place, so a failed init leaves no half-made repository behind that a
 * second init would take as done. Neo4j is not touched (ADR-0022 decision 3).
 */
public final class RepoInit {

    private static final String SCHEMA_RESOURCES = "/seed/schema/";
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");

    private RepoInit() {}

    /**
     * @param repo a path that does not exist or is an empty directory
     * @throws IOException if git or a file write fails; nothing is left at {@code repo} then
     */
    public static void create(Path repo) throws IOException {
        Path parent = Files.createDirectories(repo.toAbsolutePath().getParent());
        Path work = Files.createTempDirectory(parent, ".agentkb-init-");
        try {
            fill(work);
            // An empty directory at repo holds nothing to lose; rename cannot replace it.
            Files.deleteIfExists(repo);
            Files.move(work, repo, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // Cleanup must not hide why init failed.
            try {
                deleteTree(work);
            } catch (IOException | UncheckedIOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static void fill(Path work) throws IOException {
        require(SystemGit.run(work, false, "init", "--quiet", "--initial-branch=main"), "git init");
        Path schema = Files.createDirectories(work.resolve("schema"));
        for (String file : SCHEMA_FILES) {
            try (InputStream source = RepoInit.class.getResourceAsStream(SCHEMA_RESOURCES + file)) {
                if (source == null) {
                    throw new IOException("the server jar holds no seed/schema/" + file);
                }
                Files.copy(source, schema.resolve(file));
            }
        }
        new DocumentStoreGit(work).keepLineEndings();
        SnapshotFormat.write(
                work.resolve("snapshot"), new SnapshotFormat.Snapshot(List.of(), List.of()));
        require(
                SystemGit.run(work, false, "add", "--", ".gitattributes", "schema", "snapshot"),
                "git add");
        require(
                SystemGit.run(
                        work,
                        false,
                        SystemGit.asServer(
                                "commit", "--quiet", "-m", "kb init: schema and empty snapshot")),
                "git commit");
    }

    private static void require(SystemGit.Result result, String step) throws IOException {
        if (!result.ok()) {
            throw new IOException(step + " in the new knowledge repository failed");
        }
    }

    /** Only the init's own temporary directory. */
    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        }
    }
}
