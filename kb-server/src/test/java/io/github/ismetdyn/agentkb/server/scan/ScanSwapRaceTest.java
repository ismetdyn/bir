package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariant 9 and lead decision (D): a file is checked on what was opened, not on its path. Tracked
 * files are swapped, again and again while scans run, between a small file, a link to a file
 * outside the roots and a file larger than 1 MiB. A check of the path followed by a read of the
 * path lets a swap between the two through; a read that checks the opened channel does not. A race
 * test: on code with the gap it fails only when a swap lands in the gap, so a pass on such code is
 * no proof, while a failure always is. On correct code it runs for {@link #RACE}.
 */
class ScanSwapRaceTest {

    private static final Duration RACE = Duration.ofSeconds(6);
    private static final int SWAPPED_FILES = 8;
    private static final String INSIDE = "zz-cp5-swap-in";
    private static final String OUTSIDE = "zz-cp5-swap-outside";
    private static final String LARGE = "zz-cp5-swap-large";

    @TempDir Path dir;
    @TempDir Path outside;

    private ScanHarness harness;

    @BeforeEach
    void open() throws IOException {
        harness = ScanHarness.open(dir);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void a_file_swapped_while_the_scan_runs_is_never_read_outside_the_roots_or_above_1_mib()
            throws Exception {
        Path swap = Files.createDirectories(dir.resolve("swap"));
        String inside = ScanReadBoundaryTest.datasource(INSIDE + ".ornek.example");
        String line = "#" + "x".repeat(1023) + "\n";
        Swapper swapper =
                new Swapper(
                        new AtomicBoolean(),
                        swap.resolve("next"),
                        Files.writeString(swap.resolve("small"), inside),
                        Files.writeString(
                                swap.resolve("large"),
                                ScanReadBoundaryTest.datasource(LARGE + ".ornek.example")
                                        + line.repeat(
                                                (int) ScannerService.MAX_FILE_BYTES
                                                        / line.length())),
                        Files.writeString(
                                outside.resolve("application-prod.yml"),
                                ScanReadBoundaryTest.datasource(OUTSIDE + ".ornek.example")));
        assertThat(Files.size(swapper.large())).isGreaterThan(ScannerService.MAX_FILE_BYTES);
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < SWAPPED_FILES; i++) {
            files.put("m" + i + "/application-prod.yml", inside);
        }
        Path project = ScanProjects.project(harness.root, "zz-cp5-swap", files);
        List<Path> targets = files.keySet().stream().map(project::resolve).toList();

        int scans = 0;
        int rounds;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> swapping = executor.submit(() -> swapper.swap(targets));
            try {
                long deadline = System.nanoTime() + RACE.toNanos();
                while (System.nanoTime() < deadline && !leaked()) {
                    harness.scanner.scan(project);
                    scans++;
                }
            } finally {
                swapper.stop().set(true);
            }
            rounds = swapping.get();
        }

        assertThat(rounds).as("swap rounds").isPositive();
        assertThat(ids())
                .as("after %d scans and %d swap rounds", scans, rounds)
                .contains(INSIDE)
                .doesNotContain(OUTSIDE, LARGE);
    }

    /**
     * Turns each target into a link outside, the large file, then the small file again, where it
     * rests until its next turn: most reads see the small file, every swap opens a gap. rename(2)
     * replaces atomically, so git and the scan always find a file at the path.
     */
    private record Swapper(AtomicBoolean stop, Path next, Path small, Path large, Path link) {

        /** Swaps until stopped; returns the number of rounds over all targets. */
        int swap(List<Path> targets) throws IOException {
            int rounds = 0;
            while (!stop.get()) {
                for (Path target : targets) {
                    Files.createSymbolicLink(next, link);
                    replace(target);
                    Files.createLink(next, large);
                    replace(target);
                    Files.createLink(next, small);
                    replace(target);
                }
                rounds++;
            }
            return rounds;
        }

        private void replace(Path target) throws IOException {
            Files.move(
                    next,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean leaked() {
        List<Object> ids = ids();
        return ids.contains(OUTSIDE) || ids.contains(LARGE);
    }

    private List<Object> ids() {
        return harness.rows("MATCH (e:Entity) RETURN e.id AS id").stream()
                .map(row -> row.get("id"))
                .toList();
    }
}
