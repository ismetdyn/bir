package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotHistory;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code kb restore [--at <commit>]} (SPEC §10, ARCHITECTURE §8): the snapshot of a commit of
 * {@code <agentkb.repo>} through {@link WriteService#replaceAll}. A commit that cannot be read is a
 * refusal with a hint, and the graph is not touched. Lives in {@code write} for the same reason as
 * SnapshotImporter: {@code write} may use {@code snapshot}, not the other way round.
 */
@Component
public class SnapshotRestore {

    private static final String HINT =
            "Give a commit of the knowledge repository (git log in it lists them), or none for"
                    + " HEAD.";

    private final WriteService writes;
    private final String repo;

    SnapshotRestore(WriteService writes, @Value("${agentkb.repo:}") String repo) {
        this.writes = writes;
        this.repo = repo;
    }

    /**
     * @param at a commit; null for HEAD
     * @throws WriteRejectedException if there is no repo, or the commit or its snapshot cannot be
     *     read; nothing changed
     */
    public RestoreResult restore(@Nullable String at) {
        if (repo.isBlank()) {
            throw new WriteRejectedException(
                    "agentkb.repo is not set; there is no snapshot to restore",
                    "Start the server with --agentkb.repo=<path of the knowledge repository>.");
        }
        Snapshot snapshot;
        try {
            snapshot = SnapshotHistory.read(Path.of(repo), at);
        } catch (IOException | IllegalArgumentException e) {
            // Our own messages: they never repeat the commit name (a request value).
            throw new WriteRejectedException(e.getMessage(), HINT);
        }
        return writes.replaceAll(snapshot);
    }
}
