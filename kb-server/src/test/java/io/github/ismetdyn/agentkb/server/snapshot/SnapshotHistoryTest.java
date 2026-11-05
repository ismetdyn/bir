package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code kb restore [--at <commit>]} reads the snapshot of a commit of {@code ~/kb} with the local
 * system git (SPEC §10, ARCHITECTURE §8, invariant 3): {@code SnapshotHistory.read(repo, at)}, at
 * null meaning HEAD. {@code at} comes from a REST body, so it is untrusted: an option-shaped value
 * must never reach git as an option.
 */
class SnapshotHistoryTest {

    @TempDir Path dir;
    @TempDir Path elsewhere;

    private Snapshot older;
    private Path repo;

    @BeforeEach
    void twoCommits() {
        older = SnapshotRepos.without(SnapshotRepos.seed(), Set.of("ornek-lb"));
        repo = SnapshotRepos.repo(dir, older);
        SnapshotRepos.commit(repo, SnapshotRepos.seed(), "seed");
    }

    @Test
    void without_a_commit_it_reads_head() throws IOException {
        assertThat(SnapshotHistory.read(repo, null)).isEqualTo(SnapshotRepos.seed());
    }

    @Test
    void an_older_commit_by_relative_name_or_hash_gives_that_snapshot() throws IOException {
        String hash = ScanProjects.git(repo, "rev-parse", "HEAD~1").strip();

        assertThat(SnapshotHistory.read(repo, "HEAD~1")).isEqualTo(older);
        assertThat(SnapshotHistory.read(repo, hash)).isEqualTo(older);
    }

    /** The working tree is not the commit: a restore of HEAD ignores an uncommitted change. */
    @Test
    void an_uncommitted_change_is_not_read() throws IOException {
        SnapshotRepos.write(repo, older);

        assertThat(SnapshotHistory.read(repo, "HEAD")).isEqualTo(SnapshotRepos.seed());
    }

    @Test
    void an_unknown_commit_fails() {
        assertThatThrownBy(() -> SnapshotHistory.read(repo, "0123456789abcdef0123"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_commit_with_a_broken_row_fails() {
        SnapshotRepos.appendLine(repo, SnapshotFormat.NODES_FILE, "{\"id\": \"broken");
        ScanProjects.commitAll(repo, "broken row");

        assertThatThrownBy(() -> SnapshotHistory.read(repo, "HEAD"))
                .isInstanceOf(IOException.class);
    }

    /**
     * {@code git show --output=<file>} would write a file; the value must be refused before git
     * runs, so the file never appears.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--output=%s", "-o%s", "--upload-pack=touch %s"})
    void an_option_shaped_commit_is_refused_before_git_runs(String template) {
        Path planted = elsewhere.resolve("planted");
        String at = template.formatted(planted);

        assertThatThrownBy(() -> SnapshotHistory.read(repo, at))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(planted).doesNotExist();
    }
}
