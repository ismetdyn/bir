package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Order and locking of a completion (ADR-0025 decision 2, plan.md H4): hash, scan and copy run
 * outside the writer lock, the blob is in place ({@code .tmp} + ATOMIC_MOVE) before the node, and
 * only the node is written under the lock through WriteService (invariant 1).
 *
 * <p>The "concurrent kb_remember is not held up" case of plan.md is shown deterministically: the
 * test itself holds the writer lock, as any write would, and the blob must still appear. Done under
 * the lock, it could not.
 */
class DocumentStoreOrderTest extends DocumentControllerTestBase {

    private static final String NAME = "zz-h4-order.md";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final long POLL_MS = 50;
    private static final int BIG_BYTES = 5 * DocumentHttp.PART_BYTES;

    @Test
    void hash_scan_and_copy_run_while_another_write_holds_the_writer_lock() throws Exception {
        byte[] content = new byte[BIG_BYTES];
        byte[] text = uniqueText();
        System.arraycopy(text, 0, content, 0, text.length);
        String sha = documents.upload(content);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        store.readUnderWriteLock(
                                                tx -> {
                                                    held.countDown();
                                                    awaitQuietly(release);
                                                    return null;
                                                }));
        CompletableFuture<HttpResponse<String>> reply;
        try {
            assertThat(held.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            reply = documents.completeAsync(sha, NAME);

            boolean blobInPlace = waitFor(() -> Files.isRegularFile(blob(sha)));

            assertThat(blobInPlace)
                    .as("the blob is written while the writer lock is held elsewhere")
                    .isTrue();
            assertThat(reply).as("the node waits for the lock").isNotDone();
            assertThat(documentNodes(sha)).as("no node before the lock is free").isEmpty();
            assertThat(Files.readAllBytes(blob(sha))).isEqualTo(content);
        } finally {
            release.countDown();
            writer.join();
        }
        HttpResponse<String> done = reply.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(201);
        assertThat(documentNodes(sha)).hasSize(1);
    }

    /** Blob first: when the blob cannot be written, no node may point at a missing file. */
    @Test
    void a_blob_that_cannot_be_written_leaves_no_document_node() throws Exception {
        String sha = documents.upload(uniqueText());
        Path dir = Files.createDirectories(blob(sha).getParent());
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            HttpResponse<String> reply = documents.complete(sha, NAME);

            assertThat(reply.statusCode()).as(reply.body()).isGreaterThanOrEqualTo(400);
            assertNoLeak(reply.body());
            assertThat(documentNodes(sha)).as("no node without its blob").isEmpty();
            assertThat(blobFiles(sha)).isEmpty();
        } finally {
            Files.setPosixFilePermissions(dir, before);
        }
    }

    @Test
    void two_completions_of_the_same_content_make_one_node() throws Exception {
        String sha = documents.upload(uniqueText());

        List<CompletableFuture<HttpResponse<String>>> replies =
                List.of(documents.completeAsync(sha, NAME), documents.completeAsync(sha, NAME));

        List<Integer> statuses =
                replies.stream()
                        .map(reply -> reply.orTimeout(WAIT.toSeconds(), TimeUnit.SECONDS).join())
                        .map(HttpResponse::statusCode)
                        .toList();
        assertThat(statuses).as("exactly one created").containsOnlyOnce(201);
        assertThat(statuses).as("the other finds it or is told to retry").isSubsetOf(200, 201, 409);
        assertThat(documentNodes(sha)).hasSize(1);
        assertThat(blobFiles(sha)).as("one blob, no .tmp left").containsExactly(blob(sha));
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_MS);
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT.toSeconds() * 2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
