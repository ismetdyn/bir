package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SyncGit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Result;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code kb sync} (SPEC §10, ARCHITECTURE §8): fetch, merge the remote rows into the graph, write
 * the snapshot, commit on top of the remote branch, push; a push the remote rejects starts over
 * from the fetch, a bounded number of times. Export, merge, row count and commit run under the
 * writer lock, fetch and push outside it (plan.md "Riskler"). The rows staged for the commit must
 * match the graph's record count, signals left out (invariant 8); otherwise nothing is committed. A
 * remote that cannot be reached leaves the local commit for the next run. The graph changes only
 * through {@link WriteService} (invariant 1). Lives in {@code write} for the same reason as
 * SnapshotRestore.
 *
 * <p>On a graceful shutdown it writes the snapshot and commits it locally if it changed; the push
 * waits for the next {@code kb sync} (ADR-0022 decision 44). Spring closes it before the store it
 * depends on, so the graph is still open.
 */
@Component
public class SyncService implements DisposableBean {

    // ponytail: SPEC names no bound; 5 rounds of fetch, merge, commit and push ride out a busy
    // remote. Put the number in ADR-0022 if it ever needs tuning.
    private static final int ATTEMPTS = 5;
    private static final String NODE_COUNT = "MATCH (n) WHERE NOT n:Signal RETURN count(n) AS n";
    private static final String EDGE_COUNT =
            "MATCH (a)-[r]->(b) WHERE NOT a:Signal AND NOT b:Signal RETURN count(r) AS n";
    private static final String NO_REPO_HINT =
            "Start the server with --agentkb.repo=<path of the knowledge repository>.";
    private static final String GIT_HINT =
            "Nothing was pushed. Look at git status in the knowledge repository, then run kb sync"
                    + " again.";

    private final EmbeddedNeo4jStore store;
    private final WriteService writes;
    private final String repo;
    // The timer and a hand-run kb sync must not fetch and push over each other.
    private final ReentrantLock running = new ReentrantLock();

    SyncService(
            EmbeddedNeo4jStore store, WriteService writes, @Value("${agentkb.repo:}") String repo) {
        this.store = store;
        this.writes = writes;
        this.repo = repo;
    }

    /** One commit attempt: the commit made (null if none) and what it wrote. */
    private record Round(@Nullable String commit, int nodes, int edges, int conflicts) {}

    /**
     * @throws WriteRejectedException if there is no repo, the staged rows do not match the graph,
     *     the remote cannot be reached (the local commit waits), the push was still rejected after
     *     the last attempt, or git failed; never a force-push
     */
    public SyncResult sync() {
        if (repo.isBlank()) {
            throw new WriteRejectedException(
                    "agentkb.repo is not set; there is no knowledge repository to sync",
                    NO_REPO_HINT);
        }
        running.lock();
        try {
            return sync(new SyncGit(Path.of(repo)));
        } catch (IOException e) {
            // Our own messages (SyncGit, SnapshotHistory): git's output is never in them.
            throw new WriteRejectedException(e.getMessage(), GIT_HINT);
        } catch (UncheckedIOException e) {
            throw new WriteRejectedException(e.getCause().getMessage(), GIT_HINT);
        } finally {
            running.unlock();
        }
    }

    /**
     * The shutdown snapshot: one round without fetch or merge. Only on a repo of its own: a
     * directory inside another repository (a checkout's seed/) is not the knowledge repository, so
     * nothing is written or committed there.
     */
    @Override
    public void destroy() {
        if (repo.isBlank() || !Files.exists(Path.of(repo, ".git"))) {
            return;
        }
        running.lock();
        try {
            SyncGit git = new SyncGit(Path.of(repo));
            store.readUnderWriteLock(unused -> round(git, false));
        } finally {
            running.unlock();
        }
    }

    private SyncResult sync(SyncGit git) throws IOException {
        @Nullable String commit = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            SyncGit.Remote remote = git.fetch();
            // The read transaction is unused: it holds the writer lock over the round's own reads
            // and writes (reentrant), so no other write commits between export and commit.
            Round round =
                    store.readUnderWriteLock(
                            unused -> round(git, remote == SyncGit.Remote.REACHED));
            commit = round.commit() == null ? commit : round.commit();
            if (remote == SyncGit.Remote.NONE) {
                return result(commit, false, round);
            }
            if (remote == SyncGit.Remote.UNREACHABLE) {
                throw new WriteRejectedException(
                        "the remote of the knowledge repository cannot be reached; the snapshot"
                                + " is committed locally",
                        "The next kb sync pushes it. Check the network and git remote -v in the"
                                + " knowledge repository.");
            }
            if (git.push()) {
                return result(commit, true, round);
            }
        }
        throw new WriteRejectedException(
                "the remote rejected the push "
                        + ATTEMPTS
                        + " times; the snapshot is committed"
                        + " locally",
                "Another machine pushes at the same time, or the remote protects the branch"
                        + " against this push. Run kb sync again later; it never force-pushes.");
    }

    private Round round(SyncGit git, boolean reached) {
        try {
            @Nullable Snapshot theirs = reached ? git.mergeRemote().orElse(null) : null;
            try {
                return commitRound(git, theirs);
            } catch (IOException | RuntimeException e) {
                discard(git, e);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Round commitRound(SyncGit git, @Nullable Snapshot theirs) throws IOException {
        SnapshotExporter exporter = new SnapshotExporter(store);
        int conflicts =
                theirs == null ? 0 : writes.maintenance.mergeSnapshot(exporter.export(), theirs);
        Snapshot exported = exporter.export();
        SyncGit.Staged staged = git.stage(exported);
        long nodes = count(NODE_COUNT);
        long edges = count(EDGE_COUNT);
        if (staged.nodes() != nodes || staged.edges() != edges) {
            throw new WriteRejectedException(
                    "the snapshot has "
                            + staged.nodes()
                            + " node rows and "
                            + staged.edges()
                            + " edge rows, the graph "
                            + nodes
                            + " nodes and "
                            + edges
                            + " edges",
                    "Nothing was committed. A git filter or LFS rule on snapshot/ changes the"
                            + " rows; check .gitattributes in the knowledge repository and run kb"
                            + " check.");
        }
        return new Round(git.commit(), exported.nodes().size(), exported.edges().size(), conflicts);
    }

    /** Cleanup must not hide why the round failed. */
    private static void discard(SyncGit git, Exception cause) {
        try {
            git.discard();
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    private long count(String cypher) {
        return store.read(
                tx -> {
                    try (Result result = tx.execute(cypher)) {
                        return (Long) result.next().get("n");
                    }
                });
    }

    private static SyncResult result(@Nullable String commit, boolean pushed, Round round) {
        return new SyncResult(commit, pushed, round.nodes(), round.edges(), round.conflicts());
    }
}
