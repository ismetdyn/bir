package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * SPEC §12 (a 500-file Spring repository scans in under 5 s) as a guard on a generated multi-module
 * repository: every file has the project's own settings and the same database and cache, so each
 * entity is found in every file. The work per file must not grow with the number of files.
 *
 * <p>The check counts SecretPatterns calls, which do not depend on the machine: doubling the files
 * may at most about double them (linear), where a walk over every other file per file quadruples
 * them. Before the CP5 fix: 215,902 calls for 100 files, 851,802 for 200 (x3.95). There is no
 * wall-clock bound here: it flaked under load, so the 5 s target is measured on an idle machine at
 * CP6 (ADR-0022 decision 37).
 */
class ScanPerformanceTest {

    private static final String PROJECT = "zz-perf";
    private static final int FILES = 200;
    // Linear doubles the calls; quadratic gives about 4. Room for a per-scan constant.
    private static final double MAX_GROWTH_WHEN_FILES_DOUBLE = 2.5;

    @TempDir Path dir;

    @Test
    void secret_checks_grow_linearly_with_the_files_of_a_scan() throws IOException {
        long half = secretPatternCalls(FILES / 2);
        long full = secretPatternCalls(FILES);

        assertThat((double) full / half)
                .as(
                        "SecretPatterns calls: %d for %d files, %d for %d",
                        half, FILES / 2, full, FILES)
                .isLessThan(MAX_GROWTH_WHEN_FILES_DOUBLE);
    }

    /**
     * Every SecretPatterns call during the first scan of {@code files} generated files; the real
     * methods run, Mockito only counts (calls on this thread, where the scan runs).
     */
    private long secretPatternCalls(int files) throws IOException {
        try (ScanHarness harness = ScanHarness.open(dir.resolve("calls-" + files))) {
            Path repo = ScanProjects.project(harness.root, PROJECT, springModules(files));
            AtomicLong calls = new AtomicLong();
            try (MockedStatic<SecretPatterns> counted =
                    mockStatic(
                            SecretPatterns.class,
                            invocation -> {
                                calls.incrementAndGet();
                                return CALLS_REAL_METHODS.answer(invocation);
                            })) {
                ScanResult result = harness.scanner.scan(repo);
                assertThat(result.files()).as("precondition: every file reported").isEqualTo(files);
            }
            return calls.get();
        }
    }

    /** {@code count} modules, each with a prod profile naming the shared database and cache. */
    private static Map<String, String> springModules(int count) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String module = "svc-%03d".formatted(i);
            files.put(
                    module + "/src/main/resources/application-prod.yml",
                    """
                    server:
                      port: 8080
                    spring:
                      application:
                        name: zz-perf
                      datasource:
                        url: jdbc:postgresql://zz-perf-db.ornek.example:5432/perfdb
                      data:
                        redis:
                          host: zz-perf-cache.ornek.example
                          port: 6379
                    app:
                      client:
                        url: https://zz-perf-%s.ornek.example/api
                    """
                            .formatted(module));
        }
        return files;
    }
}
