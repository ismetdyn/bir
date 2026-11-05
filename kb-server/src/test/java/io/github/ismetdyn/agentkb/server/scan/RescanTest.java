package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC §7 rules 4 and 5 for the scanner: scanning an unchanged repository again changes nothing but
 * observed_at, and a value that left a file closes at the rescan (WritePolicy.closedByRescan for
 * the multi-valued port, rule 4 for the single-valued db_name) while the rest of the file stays.
 */
class RescanTest {

    private static final String PROD_YML = "src/main/resources/application-prod.yml";
    private static final String FACTS =
            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact {key: $key, env: 'prod'})"
                    + " RETURN f.id AS id, f.value AS value, f.valid_to AS validTo";

    @TempDir Path dir;

    private ScanHarness harness;
    private Path project;

    @BeforeEach
    void scanOnce() throws IOException {
        harness = ScanHarness.open(dir);
        project = ScanProjects.copyFixture(harness.root);
        harness.scanner.scan(project);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void a_rescan_of_an_unchanged_repository_changes_nothing_but_observed_at() {
        Map<String, Object> before = harness.state("observed_at", "updated_at");
        // An empty graph would be "unchanged" too.
        assertThat(before).containsKey("node ornek-proje").containsKey("node ornek-db");

        harness.scanner.scan(project);

        assertThat(harness.state("observed_at", "updated_at")).isEqualTo(before);
    }

    @Test
    void a_value_that_left_the_file_closes_and_the_new_one_opens() throws IOException {
        Path prod = project.resolve(PROD_YML);
        Files.writeString(
                prod,
                Files.readString(prod)
                        .replace("port: 6379", "port: 6380")
                        .replace("/ORNEKPDB", "/ORNEKPDB2"));
        ScanProjects.commitAll(project, "move redis port, rename db");
        String engineBefore = onlyOpen("ornek-db", "engine").get("id").toString();

        harness.scanner.scan(project);

        assertThat(facts("ornek-redis", "port"))
                .as("6379 closed by the rescan, 6380 open")
                .anySatisfy(f -> assertClosed(f, "6379"))
                .anySatisfy(f -> assertOpen(f, "6380"))
                .hasSize(2);
        assertThat(facts("ornek-db", "db_name"))
                .anySatisfy(f -> assertClosed(f, "ORNEKPDB"))
                .anySatisfy(f -> assertOpen(f, "ORNEKPDB2"))
                .hasSize(2);
        assertThat(
                        harness.rows(
                                "MATCH (n:Fact {value: 'ORNEKPDB2'})-[:SUPERSEDES]->(o:Fact)"
                                        + " RETURN o.value AS value"))
                .extracting(row -> row.get("value"))
                .containsExactly("ORNEKPDB");
        assertThat(onlyOpen("ornek-db", "engine").get("id"))
                .as("an unchanged value of the file is refreshed, not replaced")
                .isEqualTo(engineBefore);
    }

    /**
     * docs/SECRETS.md: the finding points at a plain-text password in a tracked file. Once the
     * password moved to an environment variable, nothing in the file backs the finding any more, so
     * the rescan closes it (rule 4: kept, with valid_to) and opens no other.
     */
    @Test
    void a_password_moved_to_an_environment_variable_closes_its_finding() throws IOException {
        assertThat(facts(ScanProjects.FIXTURE_NAME, "finding"))
                .as("precondition: the first scan opened the finding")
                .singleElement()
                .satisfies(f -> assertThat(f.get("validTo")).isNull());
        Path prod = project.resolve(PROD_YML);
        String text = Files.readString(prod);
        assertThat(text).contains(ScanProjects.PROD_PASSWORD);
        Files.writeString(
                prod,
                text.lines()
                        .map(
                                line ->
                                        line.contains(ScanProjects.PROD_PASSWORD)
                                                ? "    password: ${DB_PASSWORD}"
                                                : line)
                        .collect(Collectors.joining("\n", "", "\n")));
        ScanProjects.commitAll(project, "move the prod password to the environment");

        harness.scanner.scan(project);

        assertThat(facts(ScanProjects.FIXTURE_NAME, "finding"))
                .as("the finding is closed, not deleted, and no new one is open")
                .singleElement()
                .satisfies(f -> assertThat(f.get("validTo")).isNotNull());
        assertThat(onlyOpen("ornek-db", "db_name").get("value"))
                .as("the rest of the file stays")
                .isEqualTo("ORNEKPDB");
    }

    /**
     * One value from two files is one fact with a FROM edge to each. When one file drops it, the
     * file that still has it keeps it open, whichever of the two the scan reads first (git lists
     * application-prod.yml before bootstrap-prod.yml).
     */
    @ParameterizedTest(name = "{0} drops it")
    @ValueSource(strings = {PROD_YML, "src/main/resources/bootstrap-prod.yml"})
    void a_value_another_file_still_has_stays_open_when_one_file_drops_it(String changed)
            throws IOException {
        String yml =
                """
                app:
                  report:
                    url: https://zz-twice-svc.ornek.example/v1
                """;
        Path twice =
                ScanProjects.project(
                        harness.root,
                        "zz-twice",
                        Map.of(PROD_YML, yml, "src/main/resources/bootstrap-prod.yml", yml));
        harness.scanner.scan(twice);
        Files.writeString(twice.resolve(changed), yml.replace("/v1", "/v2"));
        ScanProjects.commitAll(twice, "one file moves to v2");

        harness.scanner.scan(twice);
        harness.scanner.scan(twice);

        assertThat(facts("zz-twice-svc", "url"))
                .as("v1 still backed by the other file: one fact, never closed; v2 opened")
                .anySatisfy(f -> assertOpen(f, "https://zz-twice-svc.ornek.example/v1"))
                .anySatisfy(f -> assertOpen(f, "https://zz-twice-svc.ornek.example/v2"))
                .hasSize(2);
    }

    private List<Map<String, Object>> facts(String entity, String key) {
        return harness.rows(FACTS, Map.of("entity", entity, "key", key));
    }

    private Map<String, Object> onlyOpen(String entity, String key) {
        List<Map<String, Object>> open =
                facts(entity, key).stream().filter(f -> f.get("validTo") == null).toList();
        assertThat(open).as("open %s.%s", entity, key).hasSize(1);
        return open.getFirst();
    }

    private static void assertClosed(Map<String, Object> fact, String value) {
        assertThat(fact.get("value")).isEqualTo(value);
        assertThat(fact.get("validTo")).isNotNull();
    }

    private static void assertOpen(Map<String, Object> fact, String value) {
        assertThat(fact.get("value")).isEqualTo(value);
        assertThat(fact.get("validTo")).isNull();
    }
}
