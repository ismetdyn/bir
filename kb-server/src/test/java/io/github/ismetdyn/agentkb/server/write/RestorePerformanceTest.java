package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC §15 and plan.md "Riskler": a restore of a 10,000-record snapshot takes under 30 s with a 512
 * MB heap, in one transaction. Measured in every build, not a tagged test: the restore runs in a
 * child JVM with {@code -Xmx512m}, because the test JVM's heap is whatever Surefire gives it.
 * {@link RestoreBenchmark} replaces 10,000 nodes and 8,000 edges with as many new ones.
 */
class RestorePerformanceTest {

    private static final long LIMIT_MILLIS = 30_000;
    private static final long CHILD_TIMEOUT_SECONDS = 180;
    private static final int NODES =
            RestoreBenchmark.ENTITIES * (1 + RestoreBenchmark.FACTS_PER_ENTITY);
    private static final int EDGES = RestoreBenchmark.ENTITIES * RestoreBenchmark.FACTS_PER_ENTITY;
    private static final Pattern REPORT =
            Pattern.compile("restore_ms=(\\d+) nodes=(\\d+) edges=(\\d+)");

    @TempDir Path dataDir;

    @Test
    void ten_thousand_records_restore_under_30_seconds_in_512_mb()
            throws IOException, InterruptedException {
        Path log = dataDir.resolve("child.log");
        Process child =
                new ProcessBuilder(
                                List.of(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-Xmx512m",
                                        "-XX:+ExitOnOutOfMemoryError",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        RestoreBenchmark.class.getName(),
                                        dataDir.resolve("data").toString()))
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertThat(exited).as("child JVM finished").isTrue();
        assertThat(child.exitValue()).as(output).isZero();
        Matcher report = REPORT.matcher(output);
        assertThat(report.find()).as(output).isTrue();
        assertThat(Integer.parseInt(report.group(2))).as("nodes").isEqualTo(NODES);
        assertThat(Integer.parseInt(report.group(3))).as("edges").isEqualTo(EDGES);
        assertThat(Long.parseLong(report.group(1))).as("restore ms").isLessThan(LIMIT_MILLIS);
    }
}
