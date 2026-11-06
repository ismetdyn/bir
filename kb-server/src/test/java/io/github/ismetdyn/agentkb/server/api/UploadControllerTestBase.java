package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;

/**
 * Upload staging, the server half of kb doc add (plan.md H3, ADR-0025 decision 1). The id is the
 * sha256 the client computed; staging only stores bytes under {@code <data-dir>/uploads} and does
 * not compare them with the id (POST /api/v1/documents does, H4). Every id is random, so the tests
 * share the server context (RunningServerTestBase) without seeing each other's uploads.
 *
 * <p>The limits are written here again on purpose, as the required values: 1 MiB per PATCH body
 * (ADR-0022 decision 17), "over 20 MB" needs git-lfs (ADR-0017, ADR-0025 decision 5), 7 days of
 * idleness before a staged file is removed. Idleness is read from the files' modification time, the
 * one clock a staged file has.
 */
abstract class UploadControllerTestBase extends RunningServerTestBase {

    protected static final int MIB = 1024 * 1024;
    protected static final Duration IDLE_LIMIT = Duration.ofDays(7);
    protected static final Duration AGED = IDLE_LIMIT.plusDays(1);
    protected static final int STAGED_LENGTH = 10;
    protected static final int STAGED_BYTES = 4;

    @Value("${agentkb.data-dir}")
    protected Path dataDir;

    @Value("${agentkb.repo}")
    protected Path repo;

    protected UploadControllerHttp uploads;

    @BeforeEach
    void client() {
        uploads = new UploadControllerHttp(port);
    }

    protected Path staging() {
        return dataDir.resolve("uploads");
    }

    /** {@link #STAGED_BYTES} of {@link #STAGED_LENGTH} bytes received. */
    static void stagePart(UploadControllerHttp uploads, String sha)
            throws IOException, InterruptedException {
        assertThat(uploads.put(sha, STAGED_LENGTH).statusCode()).isEqualTo(201);
        assertThat(uploads.patch(sha, 0, new byte[STAGED_BYTES]).statusCode()).isEqualTo(204);
    }

    /** The files under {@code staging} that {@code action} created. */
    static Set<Path> stagedBy(Path staging, StagingAction action)
            throws IOException, InterruptedException {
        Set<Path> before = filesUnder(staging);
        action.run();
        Set<Path> created = new HashSet<>(filesUnder(staging));
        created.removeAll(before);
        return created;
    }

    static Set<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return new HashSet<>(files.filter(Files::isRegularFile).toList());
        }
    }

    @FunctionalInterface
    interface StagingAction {
        void run() throws IOException, InterruptedException;
    }
}
