package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * H7 review: a PATCH or POST of an upload whose PUT answered is resumable, so a server that stops
 * answering is the same as a lost connection (exit 3, run the command again), not a failure (exit
 * 1). In-process, because a kb process waits minutes before it gives up. POST /documents may wait
 * longer than other requests: the server hashes and scans the whole file first.
 */
class JsonApiUploadTest {

    private static final long MIB = 1024 * 1024;

    @Test
    void a_stalled_upload_request_is_a_lost_upload_with_exit_three() throws IOException {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer stalled =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stalled.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        release.await(1, TimeUnit.MINUTES);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        stalled.start();
        try {
            JsonApi parts =
                    new JsonApi(
                            "http://127.0.0.1:" + stalled.getAddress().getPort(),
                            Duration.ofMillis(300),
                            "kbtest-lost");

            KbException lost =
                    catchThrowableOfType(
                            KbException.class,
                            () ->
                                    parts.request(
                                            "PATCH",
                                            "/api/v1/uploads/x",
                                            Map.of(),
                                            new byte[] {1}));

            assertThat(lost).isNotNull();
            assertThat(lost.exitCode()).isEqualTo(Kb.UNREACHABLE);
            assertThat(lost.getMessage()).isEqualTo("kbtest-lost");
        } finally {
            release.countDown();
            stalled.stop(0);
        }
    }

    @Test
    void the_completion_waits_at_least_as_long_as_any_request_and_longer_for_a_larger_file() {
        Duration small = DocCommands.Doc.Add.completionTimeout(MIB);
        Duration large = DocCommands.Doc.Add.completionTimeout(1024 * MIB);

        assertThat(small).isGreaterThanOrEqualTo(JsonApi.REQUEST_TIMEOUT);
        assertThat(large).isGreaterThan(small);
    }
}
