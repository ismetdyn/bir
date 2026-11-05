package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.ScanResult;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariant 9 and lead decision (D): every file the scan reads resolves under an allowed root.
 * git's own pointers, core.worktree and a .git file's gitdir, must not make it read outside the
 * roots, and a tracked symlink must not make it read another working copy under the same roots (CP5
 * decision 4). A refused scan and a scan that keeps the outside out are both fine; what came from
 * outside must reach neither the graph nor the report. Swaps during a scan: ScanSwapRaceTest.
 */
class ScanReadBoundaryTest {

    private static final String PROD_YML = "src/main/resources/application-prod.yml";

    @TempDir Path dir;
    @TempDir Path outside;

    private ScanHarness harness;

    @BeforeEach
    void open() throws IOException {
        harness = ScanHarness.open(dir);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void a_core_worktree_outside_the_roots_is_not_read() throws IOException {
        Path worktree = Files.createDirectories(outside.resolve("wt"));
        Files.writeString(
                worktree.resolve("application-zzwtout.yml"),
                datasource("zz-cp5-wt-outside-db.ornek.example"));
        Path repo = Files.createDirectories(harness.root.resolve("zz-cp5-wt"));
        ScanProjects.git(repo, "init", "--quiet");
        ScanProjects.git(repo, "config", "core.worktree", worktree.toString());
        ScanProjects.commitAll(repo, "worktree outside the roots");

        assertNothingFromOutside(scan(repo), "zzwtout", "zz-cp5-wt-outside");
    }

    @Test
    void a_git_file_pointing_to_a_git_dir_outside_the_roots_is_not_read() throws IOException {
        Path gitDir = Files.createDirectories(outside.resolve("gd"));
        ScanProjects.git(gitDir, "init", "--quiet");
        ScanProjects.git(
                gitDir,
                "remote",
                "add",
                "origin",
                "https://git.ornek.example/zz-out/zz-cp5-gitdir-outside.git");
        Path repo = Files.createDirectories(harness.root.resolve("zz-cp5-gd"));
        Files.writeString(repo.resolve(".git"), "gitdir: " + gitDir.resolve(".git") + "\n");
        Files.createDirectories(repo.resolve(PROD_YML).getParent());
        Files.writeString(repo.resolve(PROD_YML), datasource("zz-cp5-gd-db.ornek.example"));
        ScanProjects.commitAll(repo, "git dir outside the roots");

        assertNothingFromOutside(scan(repo), "zz-cp5-gitdir-outside", "zz-out");
    }

    /** Control: a linked worktree whose git dir is inside the roots is scanned as usual. */
    @Test
    void a_linked_worktree_whose_git_dir_is_inside_the_roots_is_scanned() {
        Path main =
                ScanProjects.project(
                        harness.root,
                        "zz-cp5-main",
                        Map.of(PROD_YML, datasource("zz-cp5-main-db.ornek.example")));
        Path linked = harness.root.resolve("zz-cp5-main-wt");
        ScanProjects.git(main, "worktree", "add", "--quiet", "-b", "wt", linked.toString());

        harness.scanner.scan(linked);

        assertThat(harness.rows("MATCH (e:Entity) RETURN e.id AS id"))
                .extracting(row -> row.get("id"))
                .contains("zz-cp5-main", "zz-cp5-main-db");
    }

    /**
     * Lead decision CP5 (4): reads are bounded to the scanned working copy, not to all allowed
     * roots. A tracked symlink to another repository under the same root does not pull that
     * repository's file into this scan.
     */
    @Test
    void a_tracked_symlink_to_another_repository_under_the_roots_is_not_read() throws IOException {
        ScanProjects.project(
                harness.root,
                "zz-cp5-neighbour",
                Map.of(PROD_YML, datasource("zz-cp5-neighbour-db.ornek.example")));
        Path repo = Files.createDirectories(harness.root.resolve("zz-cp5-linker"));
        Path link = repo.resolve(PROD_YML);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(
                link,
                link.getParent().relativize(harness.root.resolve("zz-cp5-neighbour/" + PROD_YML)));
        ScanProjects.git(repo, "init", "--quiet");
        ScanProjects.commitAll(repo, "link to a neighbour");
        assertThat(ScanProjects.git(repo, "ls-files", "--stage", PROD_YML))
                .as("precondition: git tracks the link itself")
                .startsWith("120000");

        assertNothingFromOutside(scan(repo), "zz-cp5-neighbour");
    }

    /** Control for CP5 (4): a symlink to a file of the same working copy is read as before. */
    @Test
    void a_tracked_symlink_inside_the_same_working_copy_is_read() throws IOException {
        Path repo = Files.createDirectories(harness.root.resolve("zz-cp5-selflink"));
        Files.createDirectories(repo.resolve("shared"));
        // Not a name any extractor reads, so the host can only come in through the link.
        Files.writeString(
                repo.resolve("shared/prod-settings.txt"),
                datasource("zz-cp5-selflink-db.ornek.example"));
        Path link = repo.resolve(PROD_YML);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(
                link, link.getParent().relativize(repo.resolve("shared/prod-settings.txt")));
        ScanProjects.git(repo, "init", "--quiet");
        ScanProjects.commitAll(repo, "link inside the working copy");

        harness.scanner.scan(repo);

        assertThat(harness.rows("MATCH (e:Entity) RETURN e.id AS id"))
                .extracting(row -> row.get("id"))
                .contains("zz-cp5-selflink-db");
    }

    /** The result of the scan, empty when it was refused. */
    private Optional<ScanResult> scan(Path repo) {
        try {
            return Optional.of(harness.scanner.scan(repo));
        } catch (WriteRejectedException refused) {
            return Optional.empty();
        }
    }

    private void assertNothingFromOutside(Optional<ScanResult> result, String... markers) {
        String seen = harness.state() + " " + result.map(ScanResult::toString).orElse("");
        assertThat(List.of(markers)).as("in graph and report: %s", seen).noneMatch(seen::contains);
    }

    static String datasource(String host) {
        return """
                spring:
                  datasource:
                    url: jdbc:postgresql://%s:5432/boundarydb
                """
                .formatted(host);
    }
}
