package io.github.ismetdyn.agentkb.server.scan;

import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.EXPECTED;
import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.PROJECT;
import static io.github.ismetdyn.agentkb.server.scan.ScanProjects.PROD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The merge_with_seed lines of fixtures/scanner/expected.yaml, one test each, after one scan of the
 * fixture into a graph loaded from seed/snapshot (SPEC §7: the write policy, invariant 6). A seed
 * record the scan sees again is only refreshed (observed_at moves, nothing else); one it does not
 * see stays as it was.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanMergeWithSeedTest {

    private static final Path SEED_SNAPSHOT = Path.of("..", "seed", "snapshot");
    private static final String OBSERVED_AT = "observed_at";
    private static final String UPDATED_AT = "updated_at";
    private static final String OPEN =
            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact {key: $key, env: $env})"
                    + " WHERE f.valid_to IS NULL RETURN f.id AS id, f.value AS value,"
                    + " f.source AS source";

    // Static: under PER_CLASS an instance @TempDir is not injected before @BeforeAll.
    @TempDir static Path dir;

    private ScanHarness harness;
    private States states;
    // Seed fact ids by "<entity> <key>@<env>=<value>", looked up before the scan.
    private final Map<String, String> seedFacts = new HashMap<>();

    @BeforeAll
    void scanTheFixtureOverTheSeed() throws IOException {
        assertThat(EXPECTED.get("merge_with_seed").size())
                .as("merge_with_seed has the six lines this class checks, one test each")
                .isEqualTo(6);
        harness = ScanHarness.open(dir);
        harness.writes.importRecords(SnapshotFormat.read(SEED_SNAPSHOT));
        for (String entity : List.of("ornek-app-01", "ornek-db", PROJECT)) {
            harness.rows(
                            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact)"
                                    + " WHERE f.valid_to IS NULL"
                                    + " RETURN f.id AS id, f.key + '@' + f.env + '=' + f.value AS"
                                    + " fact",
                            Map.of("entity", entity))
                    .forEach(
                            row ->
                                    seedFacts.put(
                                            entity + " " + row.get("fact"),
                                            (String) row.get("id")));
        }
        Map<String, Object> before = harness.state(UPDATED_AT);
        harness.scanner.scan(ScanProjects.copyFixture(harness.root));
        states = new States(before, harness.state(UPDATED_AT));
    }

    @AfterAll
    void close() {
        harness.close();
    }

    /** Line 1: same value from a lower source refreshes; valid_from and source user stay. */
    @Test
    void the_user_ip_of_ornek_app_01_seen_again_is_only_refreshed() {
        String id = seedFact("ornek-app-01 ip@prod=192.0.2.21");

        states.assertRefreshed(id);
        assertThat(open("ornek-app-01", "ip", "prod"))
                .filteredOn(row -> "192.0.2.21".equals(row.get("value")))
                .extracting(row -> row.get("id"))
                .as("no second fact for the same value")
                .containsExactly(id);
    }

    /** Line 2: the hostname is not scanned; the conflicting agent ip is not touched. */
    @Test
    void the_hostname_and_the_conflicting_agent_ip_of_ornek_app_01_are_unchanged() {
        states.assertUnchanged(seedFact("ornek-app-01 hostname@prod=ornek-app-01.ornek.example"));
        states.assertUnchanged(seedFact("ornek-app-01 ip@prod=192.0.2.25"));
        assertThat(open("ornek-app-01", "hostname", "prod")).hasSize(1);
    }

    /** Line 3: the four ornek-db facts are refreshed, the entity stays observed. */
    @Test
    void the_ornek_db_facts_are_refreshed_and_the_entity_stays_observed() {
        for (String fact :
                List.of(
                        "engine@prod=oracle",
                        "hostname@prod=ornek-db.ornek.example",
                        "port@prod=1521",
                        "db_name@prod=ORNEKPDB")) {
            String id = seedFact("ornek-db " + fact);
            states.assertRefreshed(id);
            assertThat(open("ornek-db", fact.substring(0, fact.indexOf('@')), "prod"))
                    .extracting(row -> row.get("id"))
                    .as(fact)
                    .containsExactly(id);
        }
        assertThat(harness.rows("MATCH (e:Entity {id: 'ornek-db'}) RETURN e.status AS status"))
                .extracting(row -> row.get("status"))
                .containsExactly("observed");
    }

    /**
     * Line 4: stack, java_version and finding refreshed; framework_version added; the user's prod
     * url, which the scan does not see, unchanged.
     */
    @Test
    void the_project_facts_are_refreshed_framework_version_added_and_the_prod_url_kept() {
        String finding =
                seedFacts.keySet().stream()
                        .filter(key -> key.startsWith(PROJECT + " finding@prod="))
                        .findFirst()
                        .orElseThrow();
        for (String key :
                List.of(
                        PROJECT + " stack@default=java-spring",
                        PROJECT + " java_version@default=21",
                        finding)) {
            states.assertRefreshed(seedFact(key));
        }
        assertThat(open(PROJECT, "finding", "prod"))
                .extracting(row -> row.get("id"))
                .as("the seed finding is the one the scanner writes")
                .containsExactly(seedFact(finding));
        assertThat(open(PROJECT, "framework_version", "default"))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("value")).isEqualTo("spring-boot 3.5.6");
                            assertThat(row.get("source")).isEqualTo("scan");
                            assertThat(states.before()).doesNotContainKey("node " + row.get("id"));
                        });
        String url = seedFact(PROJECT + " url@prod=https://ornek.example");
        states.assertUnchanged(url);
        assertThat(open(PROJECT, "url", "prod"))
                .extracting(row -> row.get("id"))
                .containsExactly(url);
    }

    /** Line 5: SPEC §7 rule 9; the scan's own secret without a value gets one, same id. */
    @Test
    void the_seed_secret_without_a_value_gets_the_value_and_keeps_its_id() {
        String id = PROJECT + "/prod/spring.datasource.password";
        assertThat(states.before()).containsKey("node " + id);

        assertThat(
                        harness.rows(
                                "MATCH (s:Secret {name: 'spring.datasource.password', env:"
                                        + " 'prod'}) RETURN s.id AS id, s.secret_value AS value,"
                                        + " s.source AS source"))
                .containsExactly(Map.of("id", id, "value", PROD_PASSWORD, "source", "scan"));
    }

    /** Line 6. */
    @Test
    void the_entities_not_in_the_seed_are_added_as_observed() {
        List<String> added =
                ExpectedScan.entities()
                        .map(args -> (String) args.get()[0])
                        .filter(id -> !states.before().containsKey("node " + id))
                        .toList();

        assertThat(added)
                .containsExactlyInAnyOrder(
                        "ornek-db-test", "ornek-redis", "ornek-app-test-01", "rapor", "giris");
        for (String id : added) {
            assertThat(
                            harness.rows(
                                    "MATCH (e:Entity {id: $id}) RETURN e.status AS status",
                                    Map.of("id", id)))
                    .extracting(row -> row.get("status"))
                    .as(id)
                    .containsExactly("observed");
        }
    }

    private String seedFact(String key) {
        assertThat(seedFacts).as("seed/snapshot has %s", key).containsKey(key);
        return seedFacts.get(key);
    }

    private List<Map<String, Object>> open(String entity, String key, String env) {
        return harness.rows(OPEN, Map.of("entity", entity, "key", key, "env", env));
    }

    /**
     * Every node and edge by id before and after the scan ({@link ScanHarness#state}), updated_at
     * left out: bookkeeping, not part of a record.
     */
    private record States(Map<String, Object> before, Map<String, Object> after) {

        /** Still open, every property as in the seed but observed_at, which moved later. */
        void assertRefreshed(String id) {
            Map<String, Object> was = props(before, id);
            Map<String, Object> is = props(after, id);
            assertThat(is).as("fact %s after the scan", id).isNotNull();
            assertThat(Instant.parse(String.valueOf(is.get(OBSERVED_AT))))
                    .as("observed_at of %s", id)
                    .isAfter(Instant.parse(String.valueOf(was.get(OBSERVED_AT))));
            Map<String, Object> wasRest = new HashMap<>(was);
            Map<String, Object> isRest = new HashMap<>(is);
            wasRest.remove(OBSERVED_AT);
            isRest.remove(OBSERVED_AT);
            assertThat(isRest).as("fact %s apart from observed_at", id).isEqualTo(wasRest);
        }

        void assertUnchanged(String id) {
            assertThat(after.get("node " + id))
                    .as("fact %s", id)
                    .isEqualTo(before.get("node " + id));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> props(Map<String, Object> state, String id) {
            List<Object> node = (List<Object>) state.get("node " + id);
            return node == null ? null : (Map<String, Object>) node.get(1);
        }
    }
}
