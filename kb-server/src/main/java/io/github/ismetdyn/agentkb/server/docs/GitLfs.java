package io.github.ismetdyn.agentkb.server.docs;

import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * git-lfs for documents over the threshold (ADR-0025 decision 5): whether git and git-lfs are
 * installed and the filter is set in the knowledge repo (kb doctor), and tracking a blob there. It
 * is set up with {@code --local} in that repo only, never in the user's global git config. git's
 * output is discarded; messages here are our own.
 */
final class GitLfs {

    // Only stops a hung git; each of these runs locally and writes at most two small files.
    private static final long TIMEOUT_SECONDS = 10;
    private static final String LFS = "lfs";

    private GitLfs() {}

    /** {@code git lfs version} runs; it writes no git config. */
    static boolean present() throws IOException {
        return runs(LFS, "version");
    }

    /** {@code git --version} runs. */
    static boolean gitPresent() throws IOException {
        return runs("--version");
    }

    /**
     * The git-lfs filter is set in {@code repo}'s own config, as {@code git lfs install --local}
     * leaves it; the user's global config does not count.
     */
    static boolean localFilterSet(Path repo) throws IOException {
        return run(repo, "config", "--local", "--get", "filter.lfs.clean");
    }

    /**
     * {@code git lfs install --local} and {@code git lfs track <path>} in {@code repo}; the second
     * writes {@code .gitattributes}, which kb sync commits. A sync remote can commit {@code
     * .gitattributes} as a link, and git lfs appends through it (CWE-59), so only a plain file or
     * none is accepted.
     *
     * @throws WriteRejectedException if {@code .gitattributes} is a link or no plain file; git lfs
     *     did not run
     * @throws IOException if either fails
     */
    static void track(Path repo, String path) throws IOException {
        Path attributes = repo.resolve(".gitattributes");
        if (Files.exists(attributes, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(attributes, LinkOption.NOFOLLOW_LINKS)) {
            throw new WriteRejectedException(
                    ".gitattributes in the knowledge repository is a link or no plain file",
                    "Make .gitattributes a file of the repo, not a link, then add the document"
                            + " again.");
        }
        if (!run(repo, LFS, "install", "--local") || !run(repo, LFS, "track", path)) {
            throw new IOException("git lfs could not track the blob in the knowledge repository");
        }
    }

    /** Outside any repository; false also when there is no git on PATH. */
    private static boolean runs(String... args) throws IOException {
        try {
            return run(null, args);
        } catch (IOException e) {
            // No git on PATH: then there is no git-lfs either.
            return false;
        }
    }

    private static boolean run(@Nullable Path repo, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
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
