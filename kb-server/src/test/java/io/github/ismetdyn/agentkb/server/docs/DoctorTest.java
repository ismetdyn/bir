package io.github.ismetdyn.agentkb.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code kb doctor} (docs/CLIENTS.md §2, plan.md H7): git, git-lfs, the knowledge repo, the git-lfs
 * filter set up with {@code --local} in that repo when its .gitattributes uses it, and an
 * owner-only data directory. Each check has a name, ok and, when it fails, a hint; no hint names a
 * path, as the answer goes to any local client.
 */
class DoctorTest {

    @TempDir Path dir;

    private Path repo;
    private Path data;

    @BeforeEach
    void healthySetup() throws IOException {
        repo = Files.createDirectories(dir.resolve("kb"));
        ScanProjects.git(repo, "init", "--quiet");
        data = Files.createDirectories(dir.resolve("data"));
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void a_healthy_setup_passes_every_check_but_maybe_git_lfs() throws IOException {
        Doctor.Report report = new Doctor(repo.toString(), data).check();

        assertThat(report.checks())
                .extracting(Doctor.Check::name)
                .containsExactly("git", "git-lfs", "repo", "lfs-filter", "data-dir");
        assertThat(oks(report))
                .containsEntry("git", true)
                .containsEntry("repo", true)
                .containsEntry("lfs-filter", true)
                .containsEntry("data-dir", true);
        assertThat(report.checks())
                .filteredOn(Doctor.Check::ok)
                .allSatisfy(check -> assertThat(check.hint()).isNull());
    }

    @Test
    void a_data_directory_others_can_read_fails_with_a_hint_that_names_no_path()
            throws IOException {
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxr-xr-x"));

        Doctor.Report report = new Doctor(repo.toString(), data).check();

        assertFailsWithHint(report, "data-dir");
        assertThat(report.ok()).isFalse();
    }

    @Test
    void a_missing_data_directory_fails() throws IOException {
        Files.delete(data);

        assertFailsWithHint(new Doctor(repo.toString(), data).check(), "data-dir");
    }

    /** No repo configured, or a directory that is no git repository. */
    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-repo"})
    void a_missing_repo_fails(String name) throws IOException {
        String configured =
                name.isEmpty() ? "" : Files.createDirectories(dir.resolve(name)).toString();

        assertFailsWithHint(new Doctor(configured, data).check(), "repo");
    }

    /** git lfs track wrote filter=lfs, but this clone never ran git lfs install --local. */
    @Test
    void lfs_attributes_without_the_local_filter_fail() throws IOException {
        Files.writeString(
                repo.resolve(".gitattributes"),
                "blobs/ab/abc filter=lfs diff=lfs merge=lfs -text\n",
                StandardCharsets.UTF_8);

        assertFailsWithHint(new Doctor(repo.toString(), data).check(), "lfs-filter");
    }

    @Test
    void lfs_attributes_with_the_local_filter_pass() throws IOException {
        Assumptions.assumeTrue(GitLfs.present(), "not run: git-lfs missing");
        Files.writeString(
                repo.resolve(".gitattributes"),
                "blobs/ab/abc filter=lfs diff=lfs merge=lfs -text\n",
                StandardCharsets.UTF_8);
        ScanProjects.git(repo, "lfs", "install", "--local");

        assertThat(oks(new Doctor(repo.toString(), data).check()))
                .containsEntry("lfs-filter", true);
    }

    private void assertFailsWithHint(Doctor.Report report, String name) {
        assertThat(report.checks())
                .filteredOn(check -> check.name().equals(name))
                .singleElement()
                .satisfies(
                        check -> {
                            assertThat(check.ok()).isFalse();
                            assertThat(check.hint()).isNotBlank();
                            assertThat(check.hint())
                                    .doesNotContain(dir.toString())
                                    .doesNotContain(System.getProperty("user.home"));
                        });
        assertThat(report.ok()).isFalse();
    }

    private static Map<String, Boolean> oks(Doctor.Report report) {
        return report.checks().stream()
                .collect(Collectors.toMap(Doctor.Check::name, Doctor.Check::ok));
    }
}
