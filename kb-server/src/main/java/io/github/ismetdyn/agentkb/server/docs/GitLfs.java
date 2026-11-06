package io.github.ismetdyn.agentkb.server.docs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * git-lfs for documents over the threshold (ADR-0025 decision 5): whether it is installed, and
 * tracking a blob in the knowledge repo. It is set up with {@code --local} in that repo only, never
 * in the user's global git config. git's output is discarded; messages here are our own.
 */
final class GitLfs {

    // Only stops a hung git; each of these runs locally and writes at most two small files.
    private static final long TIMEOUT_SECONDS = 10;

    private GitLfs() {}

    /** {@code git lfs version} runs; it writes no git config. */
    static boolean present() throws IOException {
        try {
            return run(null, "version");
        } catch (IOException e) {
            // No git on PATH: then there is no git-lfs either.
            return false;
        }
    }

    /**
     * {@code git lfs install --local} and {@code git lfs track <path>} in {@code repo}; the second
     * writes {@code .gitattributes}, which kb sync commits.
     *
     * @throws IOException if either fails
     */
    static void track(Path repo, String path) throws IOException {
        if (!run(repo, "install", "--local") || !run(repo, "track", path)) {
            throw new IOException("git lfs could not track the blob in the knowledge repository");
        }
    }

    private static boolean run(@Nullable Path repo, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "lfs"));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (repo != null) {
            // GIT_DIR pins the repository, as SystemGit does: no enclosing repository is found.
            builder.directory(repo.toFile());
            builder.environment().put("GIT_DIR", repo.resolve(".git").toString());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        }
        Process git = builder.start();
        try {
            return git.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && git.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while git lfs ran");
        } finally {
            git.destroyForcibly();
        }
    }
}
