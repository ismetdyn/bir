package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos.nodeIds;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The git side of {@code kb sync} (SPEC §10, ARCHITECTURE §8, CLAUDE.md invariants 3 and 8): a
 * commit only when the snapshot changed, and only when the rows going into it match the graph's
 * record count (signals left out); never a force-push; a push the remote rejects is retried through
 * fetch and merge a bounded number of times; a remote that cannot be reached leaves the local
 * commit waiting for the next run; a repo without a remote commits only. A refusal is a {@link
 * WriteRejectedException} (REST 400 {@code {error, hint}}, kb exit 1).
 */
class SyncGitTest {

    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    // ponytail: "bounded" has no number in SPEC; 20 local commits covers a bound of 10 attempts
    // with a merge and a snapshot commit each. Pin the real bound once ADR-0022 names it.
    private static final int ATTEMPT_CAP = 20;
    private static final String RACE_HOOK =
            """
            #!/bin/sh
            [ "$1" = committed ] || exit 0
            grep -q ' refs/heads/main$' || exit 0
            unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_COMMON_DIR GIT_PREFIX
            n=$(cat '%1$s' 2>/dev/null || echo 0)
            [ "$n" -lt %2$d ] || exit 0
            echo $((n + 1)) > '%1$s'
            cd '%3$s' || exit 0
            git fetch -q origin && git reset -q --hard origin/main || exit 0
            printf '{"id": "zz-sync-race-%%s", "labels": ["Entity", "Server"], "props": \
            {"status": "current", "summary": "zz race", "updated_at": "2026-11-01T09:00:00Z"}}\\n' \
            "$n" >> snapshot/nodes.jsonl
            git -c user.name=kbtest -c user.email=kbtest@example.invalid -c commit.gpgsign=false \
            commit -q -a -m "race $n"
            git push -q origin main
            exit 0
            """;

    @TempDir Path dir;

    private SyncMachines machines;

    @AfterEach
    void closeMachines() {
        machines.close();
    }

    @Test
    void a_repo_without_a_remote_commits_locally_and_a_second_sync_commits_nothing()
            throws IOException {
        machines = SyncMachines.local(dir);
        Machine solo =
                machines.open("solo", SnapshotRepos.repo(dir.resolve("kb"), SnapshotRepos.seed()));
        String before = solo.head();
        solo.graph.write(request(Source.USER, "zz-sync-solo", T1, fact("os", "rhel 9", PROD)));

        SyncResult first = solo.sync();

        Snapshot exported = solo.export();
        assertThat(first.pushed()).isFalse();
        assertThat(first.commit()).isEqualTo(solo.head()).isNotEqualTo(before);
        assertThat(solo.committed()).isEqualTo(machines.canonical(exported));
        assertThat(first.nodes()).isEqualTo(exported.nodes().size());
        assertThat(first.edges()).isEqualTo(exported.edges().size());

        SyncResult second = solo.sync();

        assertThat(second.commit()).as("nothing changed, no commit").isNull();
        assertThat(solo.head()).isEqualTo(first.commit());
    }

    /**
     * A clean filter that keeps one line stands for anything that changes the rows on their way
     * into git (a too broad LFS pattern turns the file into a pointer): the staged nodes.jsonl
     * holds 1 row, the graph more, so no commit and no push.
     */
    @Test
    void a_row_count_that_does_not_match_the_graph_refuses_the_commit() throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        ScanProjects.git(a.repo, "config", "filter.kbtestdrop.clean", "sed -n 1p");
        Path info = Files.createDirectories(a.repo.resolve(".git").resolve("info"));
        Files.writeString(info.resolve("attributes"), "snapshot/nodes.jsonl filter=kbtestdrop\n");
        a.graph.write(request(Source.USER, "zz-sync-count", T1, fact("os", "rhel 9", PROD)));
        String localHead = a.head();
        String remoteHead = machines.remoteHead();

        assertThatThrownBy(a::sync)
                .isInstanceOf(WriteRejectedException.class)
                .hasMessageContaining("row")
                .satisfies(e -> assertThat(((WriteRejectedException) e).hint()).isNotBlank());

        assertThat(a.head()).isEqualTo(localHead);
        assertThat(machines.remoteHead()).isEqualTo(remoteHead);
    }

    /** Another machine pushes once between A's commit and A's push: A merges it and lands. */
    @Test
    void a_push_rejected_once_is_retried_after_fetch_and_merge_and_loses_nothing()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        Path racer = machines.plainClone("racer");
        Path races = raceOnEveryLocalCommit(a, racer, 1);
        a.graph.write(request(Source.USER, "zz-sync-nff", T1, fact("os", "rhel 9", PROD)));

        SyncResult result = a.sync();

        assertThat(Files.readString(races).strip()).as("the race happened").isEqualTo("1");
        assertThat(result.pushed()).isTrue();
        String raced = ScanProjects.git(racer, "rev-parse", "HEAD").strip();
        // Exit 0 is asserted by git(): the racing commit is still in main, so no force-push.
        ScanProjects.git(machines.remote, "merge-base", "--is-ancestor", raced, "main");
        assertThat(machines.remoteFile(SnapshotFormat.NODES_FILE))
                .contains("\"zz-sync-nff\"", "\"zz-sync-race-0\"");
        assertThat(nodeIds(a.export())).contains("zz-sync-race-0");
    }

    /** The remote moves before every push of A: A gives up after a bounded number of tries. */
    @Test
    void a_push_rejected_every_time_gives_up_after_bounded_retries_and_never_forces()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        Path racer = machines.plainClone("racer");
        Path races = raceOnEveryLocalCommit(a, racer, 50);
        a.graph.write(request(Source.USER, "zz-sync-nff", T1, fact("os", "rhel 9", PROD)));

        assertThatThrownBy(a::sync)
                .isInstanceOf(WriteRejectedException.class)
                .satisfies(e -> assertThat(((WriteRejectedException) e).hint()).isNotBlank());

        int raced = Integer.parseInt(Files.readString(races).strip());
        assertThat(raced).as("retried, and bounded").isBetween(2, ATTEMPT_CAP);
        assertThat(machines.remoteHead())
                .as("the remote is the racer's, nothing forced over it")
                .isEqualTo(ScanProjects.git(racer, "rev-parse", "HEAD").strip());
        assertThat(ScanProjects.git(machines.remote, "rev-list", "--count", "main").strip())
                .as("seed commit plus every race")
                .isEqualTo(String.valueOf(1 + raced));
        assertThat(a.committed(SnapshotFormat.NODES_FILE))
                .as("the local commit waits")
                .contains("\"zz-sync-nff\"");
    }

    /** ARCHITECTURE §8: on a network failure the local commit waits for the next run. */
    @Test
    void an_unreachable_remote_keeps_the_local_commit_and_the_next_sync_pushes_it()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        ScanProjects.git(
                a.repo, "remote", "set-url", "origin", dir.resolve("missing.git").toString());
        a.graph.write(request(Source.USER, "zz-sync-offline", T1, fact("os", "rhel 9", PROD)));

        assertThatThrownBy(a::sync).isInstanceOf(WriteRejectedException.class);

        assertThat(a.committed(SnapshotFormat.NODES_FILE)).contains("\"zz-sync-offline\"");
        ScanProjects.git(a.repo, "remote", "set-url", "origin", machines.remote.toString());

        SyncResult later = a.sync();

        assertThat(later.pushed()).isTrue();
        assertThat(machines.remoteFile(SnapshotFormat.NODES_FILE)).contains("\"zz-sync-offline\"");
    }

    /**
     * A reference-transaction hook in A's repo: whenever A's main moves (a commit, merge or reset,
     * whatever git command the sync uses) and fewer than {@code limit} races ran, the racer clone
     * pushes one new record to the remote. A's next push then meets a remote that moved.
     *
     * @return the file counting the races
     */
    private Path raceOnEveryLocalCommit(Machine a, Path racer, int limit) throws IOException {
        Path races = dir.resolve("races");
        Path hook = a.repo.resolve(".git").resolve("hooks").resolve("reference-transaction");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, RACE_HOOK.formatted(races, limit, racer), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        return races;
    }
}
