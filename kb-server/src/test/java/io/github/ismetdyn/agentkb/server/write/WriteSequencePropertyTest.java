package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Invariant 5 and ADR-0022 decisions 22-23 over random write sequences on the real graph: once a
 * fact exists, valid_from and every other field stay as written; only observed_at moves forward
 * while it is open, and valid_to is set once, never before valid_from. A single-valued key keeps at
 * most one current value per env. Seeded, so a failure names the seed that reproduces it; some
 * writes arrive late (an older time), the case todo G7 left open from G3.
 */
class WriteSequencePropertyTest {

    private static final int WRITES_PER_SEQUENCE = 30;
    private static final Instant START = Instant.parse("2026-01-01T09:00:00Z");
    private static final List<Source> SOURCES =
            List.of(Source.USER, Source.USER_STATED, Source.SCAN, Source.AGENT);
    private static final List<String> ENVS = List.of("prod", "test");
    private static final List<String> HOSTNAMES =
            List.of("h1.ornek.example", "h2.ornek.example", "h3.ornek.example");
    private static final List<String> IPS = List.of("198.51.100.1", "198.51.100.2", "198.51.100.3");
    private static final int LATE_ONE_IN = 6;
    private static final String VALID_FROM = "valid_from";
    private static final String VALID_TO = "valid_to";
    private static final String OBSERVED_AT = "observed_at";

    @TempDir static Path dir;

    private static WriteHarness graph;

    @BeforeAll
    static void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
    }

    @AfterAll
    static void closeGraph() {
        graph.close();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void a_fact_never_changes_in_place_whatever_the_order_of_writes(long seed) {
        Random random = new Random(seed);
        String entity = "zz-seq-" + seed;
        Map<String, Map<String, Object>> seen = new HashMap<>();
        Instant clock = START;
        for (int step = 0; step < WRITES_PER_SEQUENCE; step++) {
            clock = clock.plus(Duration.ofHours(1 + random.nextInt(72)));
            Instant at =
                    random.nextInt(LATE_ONE_IN) == 0 ? clock.minus(Duration.ofDays(30)) : clock;
            boolean single = random.nextBoolean();
            String value = pick(random, single ? HOSTNAMES : IPS);
            graph.write(
                    request(
                            pick(random, SOURCES),
                            entity,
                            at,
                            fact(single ? "hostname" : "ip", value, pick(random, ENVS))));
            checkAgainstEarlier(seed, step, graph.facts(entity), seen);
            assertOneCurrentHostnamePerEnv(seed, step, graph.facts(entity));
        }
    }

    private static void checkAgainstEarlier(
            long seed,
            int step,
            List<Map<String, Object>> facts,
            Map<String, Map<String, Object>> seen) {
        for (Map<String, Object> now : facts) {
            String id = (String) now.get("id");
            String where = "seed " + seed + ", step " + step + ", fact " + id;
            if (now.get(VALID_TO) != null) {
                assertThat((String) now.get(VALID_TO))
                        .as(where + ": valid_to before valid_from")
                        .isGreaterThanOrEqualTo((String) now.get(VALID_FROM));
            }
            Map<String, Object> earlier = seen.put(id, now);
            if (earlier == null) {
                continue;
            }
            assertThat(fixed(now)).as(where + ": changed in place").isEqualTo(fixed(earlier));
            assertThat((String) now.get(OBSERVED_AT))
                    .as(where + ": observed_at moved back")
                    .isGreaterThanOrEqualTo((String) earlier.get(OBSERVED_AT));
            if (earlier.get(VALID_TO) != null) {
                assertThat(now.get(VALID_TO))
                        .as(where + ": reopened or re-closed")
                        .isEqualTo(earlier.get(VALID_TO));
                assertThat(now.get(OBSERVED_AT))
                        .as(where + ": a closed fact was refreshed")
                        .isEqualTo(earlier.get(OBSERVED_AT));
            }
        }
    }

    /** SPEC §7: a conflict fact waits next to the current value; it is not a second current one. */
    private static void assertOneCurrentHostnamePerEnv(
            long seed, int step, List<Map<String, Object>> facts) {
        Map<Object, Long> currentPerEnv =
                facts.stream()
                        .filter(f -> "hostname".equals(f.get("key")))
                        .filter(f -> f.get(VALID_TO) == null)
                        .filter(f -> !"conflict".equals(f.get("status")))
                        .collect(Collectors.groupingBy(f -> f.get("env"), Collectors.counting()));
        assertThat(currentPerEnv.values())
                .as("seed %d, step %d: current hostnames per env %s", seed, step, currentPerEnv)
                .allMatch(count -> count == 1);
    }

    /** Everything but what a refresh (observed_at) or a close (valid_to) may set. */
    private static Map<String, Object> fixed(Map<String, Object> fact) {
        Map<String, Object> rest = new TreeMap<>(fact);
        rest.remove(OBSERVED_AT);
        rest.remove(VALID_TO);
        rest.remove(UPDATED_AT);
        return rest;
    }

    private static <T> T pick(Random random, List<T> options) {
        return options.get(random.nextInt(options.size()));
    }
}
