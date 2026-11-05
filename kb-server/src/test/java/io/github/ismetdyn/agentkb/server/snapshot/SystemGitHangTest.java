package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decision 2: the git timeout holds even when git hangs without closing stdout, so a hung git
 * ends as an error (SnapshotRestore turns the IOException into {error, hint}), not as a request
 * thread stuck on the stream for good.
 *
 * <p>The hang is real: {@code .git/config} is a FIFO no one writes, so git blocks in open() before
 * it does anything, with its stdout still open. The timeout itself is minutes long (fsck of a large
 * repository), so the test checks what makes it hold: the caller waits in a timed wait, not in a
 * read of git's stdout, and an interrupt of that wait ends the call and the git it started.
 */
class SystemGitHangTest {

    private static final Duration SETTLE = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 50;

    @TempDir Path dir;

    private Instant started;

    @Test
    void a_git_that_hangs_with_stdout_open_leaves_the_caller_in_a_timed_wait_that_ends_it()
            throws Exception {
        Path repo = hangingRepo();
        started = Instant.now();
        CompletableFuture<Throwable> call = new CompletableFuture<>();
        Thread thread =
                Thread.ofPlatform()
                        .name("kbtest-hung-git-caller")
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        SnapshotHistory.read(repo, null);
                                        call.complete(null);
                                    } catch (Throwable e) {
                                        call.complete(e);
                                    }
                                });
        assertThat(waitForGit()).as("precondition: git started and hangs").isTrue();

        assertThat(eventuallyInTimedWait(thread))
                .as(
                        "the caller waits with a timeout, not on git's stdout (state %s)",
                        thread.getState())
                .isTrue();

        thread.interrupt();
        Throwable failure;
        try {
            failure = call.get(SETTLE.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the call is stuck although its thread was interrupted", e);
        } catch (ExecutionException e) {
            throw new AssertionError(e);
        }
        assertThat(failure).as("a hung git is an error").isInstanceOf(IOException.class);
        assertThat(eventuallyNoGit()).as("the hung git does not outlive the call").isTrue();
    }

    /** Whatever the outcome, no git of this test keeps running. */
    @AfterEach
    void killGit() {
        gitChildren().forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * A committed repository whose {@code .git/config} is then a FIFO: every git command blocks
     * reading it.
     */
    private Path hangingRepo() throws IOException, InterruptedException {
        Path repo = SnapshotRepos.repo(dir, SnapshotRepos.seed());
        Path config = repo.resolve(".git").resolve("config");
        Files.delete(config);
        Process mkfifo =
                new ProcessBuilder("mkfifo", config.toString()).redirectErrorStream(true).start();
        assertThat(mkfifo.waitFor()).as("mkfifo").isZero();
        return repo;
    }

    private boolean waitForGit() throws InterruptedException {
        return poll(() -> !gitChildren().isEmpty());
    }

    private boolean eventuallyNoGit() throws InterruptedException {
        return poll(() -> gitChildren().stream().noneMatch(ProcessHandle::isAlive));
    }

    private static boolean eventuallyInTimedWait(Thread thread) throws InterruptedException {
        return poll(() -> thread.getState() == Thread.State.TIMED_WAITING);
    }

    private static boolean poll(BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(SETTLE);
        while (Instant.now().isBefore(end)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return condition.getAsBoolean();
    }

    /** git processes this JVM started since the test began. */
    private List<ProcessHandle> gitChildren() {
        return ProcessHandle.current()
                .descendants()
                .filter(ProcessHandle::isAlive)
                .filter(
                        process ->
                                process.info().command().orElse("").endsWith("/git")
                                        && process.info()
                                                .startInstant()
                                                .map(
                                                        at ->
                                                                started != null
                                                                        && !at.isBefore(
                                                                                started
                                                                                        .minusSeconds(
                                                                                                1)))
                                                .orElse(true))
                .toList();
    }
}
