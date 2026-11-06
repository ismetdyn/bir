package io.github.ismetdyn.agentkb.server.scan;

import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local reads of a git working copy with the system git: top level, HEAD, remote and file lists. No
 * command here talks to a remote (invariant 3). The repository's own fsmonitor hook is turned off,
 * as it would run a program from the scanned repo's config.
 */
final class LocalGit {

    // ls-files of a large working copy takes well under a second; this only stops a hung git.
    private static final long TIMEOUT_SECONDS = 60;
    private static final Logger LOG = LoggerFactory.getLogger(LocalGit.class);
    private static final List<String> GIT = List.of("git", "-c", "core.fsmonitor=false");

    private final Path dir;

    LocalGit(Path dir) {
        this.dir = dir;
    }

    Optional<Path> topLevel() {
        return run("rev-parse", "--show-toplevel").map(out -> Path.of(out.strip()));
    }

    /**
     * The git dir and the common git dir (they differ for a linked worktree), absolute; a {@code
     * .git} file or {@code core.worktree} may put them anywhere.
     */
    List<Path> gitDirs() {
        return run("rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir")
                .map(out -> out.lines().filter(line -> !line.isBlank()).map(Path::of).toList())
                .orElse(List.of());
    }

    Optional<String> head() {
        return run("rev-parse", "--verify", "--quiet", "HEAD")
                .map(String::strip)
                .filter(commit -> !commit.isEmpty());
    }

    /** The origin remote, else the first one; as configured, not normalized. */
    Optional<String> remote() {
        return run("remote", "get-url", "origin")
                .or(
                        () ->
                                run("remote")
                                        .flatMap(names -> names.lines().findFirst())
                                        .flatMap(name -> run("remote", "get-url", name.strip())))
                .map(String::strip)
                .filter(url -> !url.isEmpty());
    }

    /** {@code git ls-files} under the directory with {@code options}, paths from the top level. */
    List<String> files(String... options) {
        List<String> args = new ArrayList<>(List.of("ls-files", "-z", "--full-name"));
        args.addAll(Arrays.asList(options));
        return run(args.toArray(String[]::new))
                .map(out -> Arrays.stream(out.split("\0")).filter(path -> !path.isEmpty()).toList())
                .orElse(List.of());
    }

    /**
     * The output of a git command, empty if it exits with an error. The output goes to a temporary
     * file only the owner reads, not a pipe, so a git that hangs is cut at the timeout instead of
     * holding the reading thread (ADR-0022 decision 40); the file holds paths and ids, no file
     * content, and is deleted before this returns.
     */
    private Optional<String> run(String... args) {
        List<String> command = new ArrayList<>(GIT);
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Path output = tempFile();
        try {
            Process git = start(builder.redirectOutput(output.toFile()));
            try {
                if (!git.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("git " + args[0] + " did not finish");
                }
            } finally {
                git.destroyForcibly();
            }
            return git.exitValue() == 0
                    ? Optional.of(new String(Files.readAllBytes(output), StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("reading the output of git failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while git ran", e);
        } finally {
            delete(output);
        }
    }

    private static Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            WriteRejectedException rejected =
                    new WriteRejectedException(
                            "git could not be run on this machine",
                            "Install git; kb_scan reads the working copy with the local git"
                                    + " command.");
            rejected.initCause(e);
            throw rejected;
        }
    }

    // Files.createTempFile makes it rw------- on a POSIX file system.
    private static Path tempFile() {
        try {
            return Files.createTempFile("agentkb-scan-git-", ".out");
        } catch (IOException e) {
            throw new UncheckedIOException("no temporary file for the output of git", e);
        }
    }

    // Logged, not thrown: it runs in a finally and must not hide the error of the git call.
    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("event=git_output_not_deleted error={}", e.getClass().getName());
        }
    }
}
