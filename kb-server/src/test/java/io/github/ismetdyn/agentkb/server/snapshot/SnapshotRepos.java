package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@code ~/kb} repository for the G12 tests (ARCHITECTURE §8): {@code schema/} from seed/schema,
 * {@code snapshot/} as given, each state committed with the local system git (invariant 3). The
 * seed is the golden data (seed/README.md).
 */
public final class SnapshotRepos {

    public static final Path SEED_SNAPSHOT = Path.of("..", "seed", "snapshot");

    /** seed/README.md: ornek-app-01's ip 192.0.2.25 from an agent waits as a conflict. */
    public static final String SEED_CONFLICT_FACT = "01M61GCWC0YQ7CBPWNDRQC5FX5";

    private static final Path SEED_SCHEMA = Path.of("..", "seed", "schema");
    private static final List<String> SCHEMA_FILES =
            List.of("types.yaml", "relations.yaml", "facts.yaml");
    private static final String SNAPSHOT_DIRECTORY = "snapshot";

    private SnapshotRepos() {}

    public static Snapshot seed() {
        try {
            return SnapshotFormat.read(SEED_SNAPSHOT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code snapshot} without the nodes {@code ids} and without every edge that touches one. */
    public static Snapshot without(Snapshot snapshot, Set<String> ids) {
        return new Snapshot(
                snapshot.nodes().stream().filter(node -> !ids.contains(node.id())).toList(),
                snapshot.edges().stream()
                        .filter(edge -> !ids.contains(edge.from()) && !ids.contains(edge.to()))
                        .toList());
    }

    public static List<String> nodeIds(Snapshot snapshot) {
        return snapshot.nodes().stream().map(SnapshotFormat.Node::id).toList();
    }

    public static List<String> edgeIds(Snapshot snapshot) {
        return snapshot.edges().stream().map(SnapshotFormat.Edge::id).toList();
    }

    /** A new repository under {@code dir/repo} whose first commit holds {@code snapshot}. */
    public static Path repo(Path dir, Snapshot snapshot) {
        Path repo = dir.resolve("repo");
        try {
            Path schema = Files.createDirectories(repo.resolve("schema"));
            for (String file : SCHEMA_FILES) {
                Files.copy(SEED_SCHEMA.resolve(file), schema.resolve(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ScanProjects.git(repo, "init", "--quiet");
        commit(repo, snapshot, "first snapshot");
        return repo;
    }

    /**
     * Copies seed/blobs into {@code repo/blobs}, uncommitted: the seed's Document then has its blob
     * and kb check finds no missing-blob.
     */
    public static void copySeedBlobs(Path repo) {
        Path seedBlobs = Path.of("..", "seed", "blobs");
        try (Stream<Path> files = Files.walk(seedBlobs)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = repo.resolve("blobs").resolve(seedBlobs.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code snapshot} into {@code repo/snapshot} and commits it. */
    public static void commit(Path repo, Snapshot snapshot, String message) {
        write(repo, snapshot);
        ScanProjects.commitAll(repo, message);
    }

    /** Writes {@code snapshot} into {@code repo/snapshot} without committing it. */
    public static void write(Path repo, Snapshot snapshot) {
        try {
            SnapshotFormat.write(repo.resolve(SNAPSHOT_DIRECTORY), snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends a raw line to {@code repo/snapshot/<file>}; the caller commits if it wants to. */
    public static void appendLine(Path repo, String file, String line) {
        try {
            Path path = repo.resolve(SNAPSHOT_DIRECTORY).resolve(file);
            Files.writeString(
                    path,
                    Files.readString(path, StandardCharsets.UTF_8) + line + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Overwrites every loose object of {@code repo} with bytes that are no zlib stream, so {@code
     * git fsck} has to fail. A fresh repository has only loose objects.
     */
    public static void corruptObjects(Path repo) {
        try (Stream<Path> files = Files.walk(repo.resolve(".git").resolve("objects"))) {
            List<Path> loose =
                    files.filter(Files::isRegularFile)
                            .filter(file -> file.getParent().getFileName().toString().length() == 2)
                            .toList();
            assertThat(loose).as("precondition: loose objects to corrupt").isNotEmpty();
            for (Path object : loose) {
                assertThat(object.toFile().setWritable(true)).as("writable %s", object).isTrue();
                Files.write(object, "kbtest corrupted object".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
