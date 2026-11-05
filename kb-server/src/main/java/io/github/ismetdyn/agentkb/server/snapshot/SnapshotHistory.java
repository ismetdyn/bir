package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The snapshot of one commit of {@code ~/kb}, for {@code kb restore [--at <commit>]} (SPEC §10,
 * ARCHITECTURE §8), read with the local system git; the working tree is not looked at. {@code at}
 * comes from a REST body, so it never reaches git as an option, and no message repeats it.
 */
public final class SnapshotHistory {

    private static final String HEAD = "HEAD";
    private static final String SNAPSHOT = "snapshot/";

    private SnapshotHistory() {}

    /**
     * @param at a commit name git understands (hash, HEAD~1, a tag); null for HEAD
     * @throws IllegalArgumentException if {@code at} is empty or starts with {@code -}; git did not
     *     run
     * @throws IOException if there is no such commit, it has no snapshot or a row is broken
     */
    public static Snapshot read(Path repo, @Nullable String at) throws IOException {
        String name = at == null ? HEAD : at;
        if (name.isBlank() || name.startsWith("-")) {
            throw new IllegalArgumentException(
                    "a commit name is not empty and does not start with -");
        }
        SystemGit.Result commit =
                SystemGit.run(repo, false, "rev-parse", "--verify", "--quiet", name + "^{commit}");
        if (!commit.ok()) {
            throw new IOException("no such commit in the knowledge repository");
        }
        String hash = commit.output().strip();
        return SnapshotFormat.parse(
                file(repo, hash, SnapshotFormat.NODES_FILE),
                file(repo, hash, SnapshotFormat.EDGES_FILE));
    }

    private static String file(Path repo, String hash, String name) throws IOException {
        SystemGit.Result blob =
                SystemGit.run(repo, false, "cat-file", "blob", hash + ":" + SNAPSHOT + name);
        if (!blob.ok()) {
            throw new IOException("commit " + hash + " has no " + SNAPSHOT + name);
        }
        return blob.output();
    }
}
