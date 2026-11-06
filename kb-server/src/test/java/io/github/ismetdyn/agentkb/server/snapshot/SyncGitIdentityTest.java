package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb sync} on a machine where git has no identity of its own (no {@code ~/.gitconfig}, no
 * system config, no identity in the environment): the merge of a remote that moved on and the merge
 * commit take the server's fixed identity, as every other commit of SyncGit does.
 *
 * <p>The JVM's environment cannot be changed in-process, so SyncGit runs in a child JVM ({@link
 * #main}) whose environment has no git identity.
 */
class SyncGitIdentityTest {

    private static final long CHILD_TIMEOUT_SECONDS = 120;
    private static final List<String> IDENTITY_VARIABLES =
            List.of(
                    "GIT_AUTHOR_NAME",
                    "GIT_AUTHOR_EMAIL",
                    "GIT_COMMITTER_NAME",
                    "GIT_COMMITTER_EMAIL",
                    "EMAIL");

    @TempDir Path dir;

    @Test
    void a_remote_that_moved_on_merges_and_commits_without_a_global_git_identity()
            throws Exception {
        Path seed = SnapshotRepos.repo(dir.resolve("seed"), SnapshotRepos.seed());
        Path remote = dir.resolve("remote.git");
        ScanProjects.git(dir, "init", "--bare", "--quiet", remote.toString());
        ScanProjects.git(seed, "remote", "add", "origin", remote.toString());
        ScanProjects.git(seed, "push", "--quiet", "origin", "main");
        Path kb = cloneWithCommit(remote, "kb", "local.txt");
        Path other = cloneWithCommit(remote, "other", "other.txt");
        ScanProjects.git(other, "push", "--quiet", "origin", "main");
        String localTip = ScanProjects.git(kb, "rev-parse", "HEAD").strip();
        String remoteTip = ScanProjects.git(other, "rev-parse", "HEAD").strip();
        Path home = Files.createDirectories(dir.resolve("home"));

        Child child = runWithoutIdentity(home, kb, dir.resolve("child.out"));

        assertThat(child.exitCode()).as("child output: %s", child.output()).isZero();
        assertThat(ScanProjects.git(kb, "rev-parse", "HEAD^1", "HEAD^2").lines())
                .as("HEAD is a merge of the local and the remote tip")
                .containsExactly(localTip, remoteTip);
        assertThat(ScanProjects.git(kb, "ls-tree", "--name-only", "HEAD").lines())
                .contains("local.txt", "other.txt");
    }

    /** The child: fetch, merge and commit as {@code kb sync} does; exit 1 on a refusal. */
    public static void main(String[] args) {
        SyncGit git = new SyncGit(Path.of(args[0]));
        try {
            if (git.fetch() != SyncGit.Remote.REACHED) {
                System.out.println("the remote was not reached");
                System.exit(2);
            }
            git.mergeRemote();
            System.out.println("commit " + git.commit());
        } catch (IOException e) {
            System.out.println("refused: " + e.getMessage());
            System.exit(1);
        }
    }

    private Path cloneWithCommit(Path remote, String name, String file) throws IOException {
        Path clone = dir.resolve(name);
        ScanProjects.git(dir, "clone", "--quiet", remote.toString(), clone.toString());
        Files.writeString(clone.resolve(file), name + "\n", StandardCharsets.UTF_8);
        ScanProjects.commitAll(clone, name + " commit");
        return clone;
    }

    private record Child(int exitCode, String output) {}

    private static Child runWithoutIdentity(Path home, Path kb, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder =
                new ProcessBuilder(
                                java,
                                "-cp",
                                System.getProperty("java.class.path"),
                                SyncGitIdentityTest.class.getName(),
                                kb.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        Map<String, String> env = builder.environment();
        IDENTITY_VARIABLES.forEach(env::remove);
        env.keySet().removeIf(name -> name.startsWith("GIT_CONFIG"));
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        // Whether git can guess an email from the host name depends on the machine; with guessing
        // off the test fails the same way everywhere a missing identity matters.
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "user.useConfigOnly");
        env.put("GIT_CONFIG_VALUE_0", "true");
        Process process = builder.start();
        boolean finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        process.destroyForcibly();
        assertThat(finished).as("the child JVM finished").isTrue();
        return new Child(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }
}
