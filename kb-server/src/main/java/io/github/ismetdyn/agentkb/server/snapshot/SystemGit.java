package io.github.ismetdyn.agentkb.server.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The local system git on {@code ~/kb} (ARCHITECTURE §8). Only {@code kb sync}'s fetch and push
 * (SyncGit) reach the network (invariant 3): no lazy fetch from a promisor remote, no prompt.
 * GIT_DIR pins the repository, so a repo directory without {@code .git} fails instead of git
 * finding an enclosing repository.
 */
final class SystemGit {

    // Only stops a hung git; fsck of a large repository takes a while.
    private static final long TIMEOUT_SECONDS = 300;

    /** {@code output} is stdout, with stderr appended when the caller asked for it. */
    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private SystemGit() {}

    /**
     * @param withErrors stderr goes into the output; otherwise it is discarded, as it may quote an
     *     argument that came from a request
     * @throws IOException if git cannot be run or does not finish
     */
    static Result run(Path repo, boolean withErrors, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
        if (withErrors) {
            builder.redirectErrorStream(true);
        } else {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        builder.environment().put("GIT_DIR", repo.resolve(".git").toString());
        builder.environment().put("GIT_NO_LAZY_FETCH", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        // Output goes to an owner-only file, not a pipe: a git that hangs with stdout open would
        // block a read of the pipe for good, past the timeout.
        Path output = Files.createTempFile("agentkb-git-", ".out");
        try {
            builder.redirectOutput(output.toFile());
            Process git = builder.start();
            try {
                if (!git.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("git " + args[0] + " did not finish");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while git " + args[0] + " ran", e);
            } finally {
                git.destroyForcibly();
            }
            return new Result(
                    git.exitValue(),
                    new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
