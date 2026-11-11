package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code kb sync} may touch in {@code ~/kb} besides the snapshot (todo G13 LOW items): the
 * commit holds only {@code snapshot/}, never other files the user staged; the push names the branch
 * in full, so a branch name starting with {@code +} never becomes a forced refspec (invariant 8); a
 * remote with an unrelated history is refused with a hint that says so.
 */
class SyncScopeTest {

    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final String FOREIGN = "notes.txt";
    private static final List<String> SNAPSHOT_FILES =
            List.of("snapshot/nodes.jsonl", "snapshot/edges.jsonl");
    // git check-ref-format --branch accepts it; "+kb:+kb" as a push refspec forces "kb".
    private static final String PLUS_BRANCH = "+kb";

    @TempDir Path dir;

    private SyncMachines machines;

    @AfterEach
    void closeMachines() {
        if (machines != null) {
            machines.close();
        }
    }

    @Test
    void a_sync_commits_only_the_snapshot_and_leaves_a_foreign_staged_file_staged()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        upToDate(a);
        stageForeignFile(a.repo);
        a.graph.write(request(Source.USER, "zz-sync-scope", T1, fact("os", "rhel 9", PROD)));

        SyncResult result = a.sync();

        assertThat(result.commit()).isNotNull();
        assertThat(changedIn(a.repo, "HEAD"))
                .as("files in the sync commit")
                .isSubsetOf(SNAPSHOT_FILES);
        assertThat(ScanProjects.git(a.repo, "diff", "--cached", "--name-only").lines())
                .as("the user's staged file is still staged, not committed")
                .containsExactly(FOREIGN);
        assertThat(
                        ScanProjects.git(machines.remote, "ls-tree", "-r", "--name-only", "main")
                                .lines())
                .doesNotContain(FOREIGN);
    }

    @Test
    void a_foreign_staged_file_alone_makes_no_sync_commit() throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        upToDate(a);
        String before = a.head();
        stageForeignFile(a.repo);

        SyncResult result = a.sync();

        assertThat(result.commit()).as("the graph did not change").isNull();
        assertThat(a.head()).isEqualTo(before);
        assertThat(ScanProjects.git(a.repo, "diff", "--cached", "--name-only").lines())
                .containsExactly(FOREIGN);
    }

    /**
     * A's branch is {@code +kb} and A also has a local branch {@code kb} left on the seed commit.
     * The remote's {@code +kb} holds a commit of another machine. A push of {@code "+kb:+kb"} would
     * force {@code kb} over it; the explicit {@code refs/heads/+kb:refs/heads/+kb} lands A's merged
     * commit on top of it.
     */
    @Test
    void a_branch_name_starting_with_plus_is_pushed_without_force_and_loses_no_remote_commit()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        ScanProjects.git(a.repo, "checkout", "--quiet", "-b", PLUS_BRANCH);
        ScanProjects.git(a.repo, "push", "--quiet", "origin", plusRef() + ":" + plusRef());
        ScanProjects.git(a.repo, "branch", "kb");
        Path racer = machines.plainClone("racer");
        ScanProjects.git(racer, "checkout", "--quiet", PLUS_BRANCH);
        Files.writeString(racer.resolve("racer.txt"), "zz racer\n", StandardCharsets.UTF_8);
        ScanProjects.commitAll(racer, "racer commit");
        ScanProjects.git(racer, "push", "--quiet", "origin", plusRef() + ":" + plusRef());
        String raced = ScanProjects.git(racer, "rev-parse", "HEAD").strip();
        a.graph.write(request(Source.USER, "zz-sync-plus", T1, fact("os", "rhel 9", PROD)));

        SyncResult result = a.sync();

        assertThat(result.pushed()).isTrue();
        String remoteTip = ScanProjects.git(machines.remote, "rev-parse", plusRef()).strip();
        assertThat(remoteTip).as("the remote got A's commit").isEqualTo(a.head());
        // Exit 0 is asserted by git(): the other machine's commit is still in the remote branch.
        ScanProjects.git(machines.remote, "merge-base", "--is-ancestor", raced, plusRef());
    }

    @Test
    void a_remote_with_an_unrelated_history_is_refused_with_a_hint_that_says_so()
            throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        Path stranger = SnapshotRepos.repo(dir.resolve("stranger"), SnapshotRepos.seed());
        // Same tree, identity and message within one second would be the seed's very commit: a
        // message of its own makes the stranger's root another commit.
        ScanProjects.git(stranger, "commit", "--quiet", "--amend", "--message", "stranger root");
        assertThat(ScanProjects.git(stranger, "rev-parse", "HEAD").strip())
                .as("precondition: the stranger's root is not the seed commit")
                .isNotEqualTo(machines.seedCommit);
        ScanProjects.git(stranger, "remote", "add", "origin", machines.remote.toString());
        // Test setup only: another history replaces the remote's main.
        ScanProjects.git(stranger, "push", "--quiet", "--force", "origin", "main");
        String remoteHead = machines.remoteHead();
        String localHead = a.head();
        a.graph.write(request(Source.USER, "zz-sync-unrelated", T1, fact("os", "rhel 9", PROD)));

        WriteRejectedException refused =
                catchThrowableOfType(WriteRejectedException.class, a::sync);

        assertThat(refused).as("the sync went through").isNotNull();
        assertThat((refused.getMessage() + " " + refused.hint()).toLowerCase(Locale.ROOT))
                .contains("unrelated");
        assertThat(refused.hint()).isNotBlank();
        assertThat(machines.remoteHead()).isEqualTo(remoteHead);
        assertThat(a.head()).isEqualTo(localHead);
    }

    /**
     * The seed predates ADR-0027, so a first sync commits the .gitattributes line endings (decision
     * 8); the checks here start after it.
     */
    private static void upToDate(Machine machine) throws IOException {
        machine.sync();
        assertThat(ScanProjects.git(machine.repo, "status", "--porcelain")).isBlank();
    }

    private static void stageForeignFile(Path repo) throws IOException {
        Files.writeString(repo.resolve(FOREIGN), "the user's own note\n", StandardCharsets.UTF_8);
        ScanProjects.git(repo, "add", "--", FOREIGN);
    }

    private static List<String> changedIn(Path repo, String commit) {
        return ScanProjects.git(repo, "show", "--name-only", "--format=", commit)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String plusRef() {
        return "refs/heads/" + PLUS_BRANCH;
    }
}
