package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Checks of work the server does in the background, polled every 100 ms. */
final class Poll {

    private static final long INTERVAL_MS = 100;

    private Poll() {}

    /** Fails unless {@code condition} holds within {@code limit}. */
    static void until(Duration limit, BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as("within %s: %s", limit, what).isTrue();
            TimeUnit.MILLISECONDS.sleep(INTERVAL_MS);
        }
    }

    /** Fails if {@code condition} stops holding at any check during {@code period}. */
    static void stays(Duration period, BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + period.toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(condition.getAsBoolean()).as("for %s: %s", period, what).isTrue();
            TimeUnit.MILLISECONDS.sleep(INTERVAL_MS);
        }
    }
}
