package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The git steps of {@code kb sync} on {@code ~/kb} (SPEC §10, ARCHITECTURE §8) through {@link
 * SystemGit}: fetch and push of the branch HEAD is on to and from {@code origin}, the only git that
 * reaches the network (invariant 3), and the local merge and commit between them. Never a
 * force-push (invariant 8). git's own output is discarded, as a remote url may carry a token; every
 * message here is our own.
 */
public final class SyncGit {

    private static final String ORIGIN = "origin";
    private static final String NODES = "snapshot/" + SnapshotFormat.NODES_FILE;
    private static final String EDGES = "snapshot/" + SnapshotFormat.EDGES_FILE;
    private final Path repo;
    private @Nullable String branch;

    /** The rows git holds in the index for the two files after {@link #stage}. */
    public record Staged(long nodes, long edges) {}

    public SyncGit(Path repo) {
        this.repo = repo;
    }

    /** What {@link #fetch} found. */
    public enum Remote {
        /** The repo has no {@code origin}: sync only commits. */
        NONE,
        REACHED,
        /** It could not be reached or refused the fetch. */
        UNREACHABLE
    }

    public Remote fetch() throws IOException {
        if (!git("config", "--get", "remote." + ORIGIN + ".url").ok()) {
            return Remote.NONE;
        }
        return git("fetch", "--quiet", "--no-tags", ORIGIN).ok()
                ? Remote.REACHED
                : Remote.UNREACHABLE;
    }

    /**
     * Brings the fetched remote branch into HEAD unless HEAD already holds it: a fast-forward, or a
     * merge left uncommitted for {@link #commit}. The snapshot files take our side, as the caller
     * rewrites them from the merged graph; other files merge as git merges them. The snapshot files
     * go back to HEAD first, so a sync that stopped half-way does not block the merge.
     *
     * @return the snapshot of the remote branch; empty if the remote has no such branch yet
     * @throws IOException if its snapshot cannot be read or git cannot merge; a merge is aborted
     */
    public Optional<Snapshot> mergeRemote() throws IOException {
        String remote = "refs/remotes/" + ORIGIN + "/" + branch();
        if (!git("rev-parse", "--verify", "--quiet", remote + "^{commit}").ok()) {
            return Optional.empty();
        }
        if (!git("merge-base", "HEAD", remote).ok()) {
            throw new IOException(
                    "the remote branch has an unrelated history: it shares no commit with the"
                            + " knowledge repository, so nothing was merged or pushed. Point origin"
                            + " at this repository's own remote, or clone that remote again");
        }
        Snapshot theirs = SnapshotHistory.read(repo, remote);
        if (!git("merge-base", "--is-ancestor", remote, "HEAD").ok()) {
            git("checkout", "--quiet", "HEAD", "--", NODES, EDGES);
            if (!git("merge", "--quiet", "--no-commit", "-X", "ours", remote).ok()) {
                discard();
                throw new IOException(
                        "the remote branch cannot be merged into the knowledge repository");
            }
        }
        return Optional.of(theirs);
    }

    /** Writes {@code snapshot} into the working tree and stages both files. */
    public Staged stage(Snapshot snapshot) throws IOException {
        SnapshotFormat.write(repo.resolve("snapshot"), snapshot);
        require(git("add", "--", NODES, EDGES), "git add of the snapshot failed");
        return new Staged(stagedRows(NODES), stagedRows(EDGES));
    }

    /**
     * Commits what {@link #stage} staged, and the merge {@link #mergeRemote} left open. Outside a
     * merge only the two snapshot files are committed: other files the user staged stay staged.
     *
     * @return the new commit; null if the snapshot did not change and no merge was open
     */
    public @Nullable String commit() throws IOException {
        boolean merging = git("rev-parse", "--verify", "--quiet", "MERGE_HEAD").ok();
        if (!merging && git("diff", "--cached", "--quiet", "--", NODES, EDGES).ok()) {
            return null;
        }
        // git refuses a commit of only some paths during a merge; a merge starts from a clean
        // index, so then the index holds only the merge and the snapshot.
        String[] commit =
                merging
                        ? new String[] {"commit", "--quiet", "-m", "kb sync: snapshot"}
                        : new String[] {
                            "commit", "--quiet", "-m", "kb sync: snapshot", "--", NODES, EDGES
                        };
        require(git(SystemGit.asServer(commit)), "git commit of the snapshot failed");
        return git("rev-parse", "HEAD").output().strip();
    }

    /**
     * @return false if the remote could not be reached or refused the push (it moved, or it
     *     protects the branch)
     */
    public boolean push() throws IOException {
        // Full ref names: a branch named "+kb" must not become the forced refspec "+kb:+kb".
        String head = "refs/heads/" + branch();
        return git("push", "--quiet", ORIGIN, head + ":" + head).ok();
    }

    /**
     * After a refusal: aborts a merge this sync opened and puts the snapshot files back to HEAD.
     */
    public void discard() throws IOException {
        if (git("rev-parse", "--verify", "--quiet", "MERGE_HEAD").ok()) {
            git("merge", "--abort");
        }
        git("checkout", "--quiet", "HEAD", "--", NODES, EDGES);
    }

    private long stagedRows(String file) throws IOException {
        SystemGit.Result blob = git("cat-file", "blob", ":" + file);
        require(blob, "git has no staged " + file);
        return blob.output().chars().filter(c -> c == '\n').count();
    }

    private String branch() throws IOException {
        if (branch == null) {
            SystemGit.Result head = git("symbolic-ref", "--quiet", "--short", "HEAD");
            require(head, "the knowledge repository is not on a branch");
            branch = head.output().strip();
        }
        return branch;
    }

    private SystemGit.Result git(String... args) throws IOException {
        return SystemGit.run(repo, false, args);
    }

    private static void require(SystemGit.Result result, String message) throws IOException {
        if (!result.ok()) {
            throw new IOException(message);
        }
    }
}
