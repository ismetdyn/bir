package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ARCHITECTURE §5 step 2, ADR-0022 decision 31 (g) for the scanner: a host the scan finds is the
 * entity that already holds it as an open hostname or ip fact, whatever its id, as kb_remember
 * resolves it. The scan writes onto that entity and points the project's relation there; it does
 * not create a second entity for the same machine.
 */
class ScanEntityResolutionTest {

    private static final Instant KNOWN_AT = Instant.parse("2026-01-15T09:00:00Z");
    private static final String PROD = "prod";
    private static final String ENTITY_FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                    + " RETURN f.key + '=' + f.value + '@' + f.env + '/' + f.source AS fact";
    private static final String DEPENDS_ON =
            "MATCH (:Entity {id: $id})-[r:DEPENDS_ON]->(t:Entity) RETURN t.id + '@' + r.env AS edge";
    private static final String ENTITY_IDS = "MATCH (e:Entity) RETURN e.id AS id";

    @TempDir Path dir;

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
    void a_host_in_an_internal_domain_is_written_onto_the_entity_holding_that_hostname() {
        String name = "zz-g10-known";
        known("zz-legacy-oracle", "hostname", name + "-db.ornek.example");
        Path project =
                ScanProjects.springProject(
                        harness.root,
                        name,
                        "kbtest-fake-0c7d2e9a41b65f38",
                        "kbtest-fake-5a91e3c08d2b7f64");

        harness.scanner.scan(project);

        assertWrittenOnto("zz-legacy-oracle", name, name + "-db");
        assertThat(facts("zz-legacy-oracle"))
                .contains("engine=oracle@prod/scan", "db_name=ZZPDB@prod/scan")
                .filteredOn(fact -> fact.startsWith("hostname="))
                .as("one open hostname: the known one, not a second from the scan")
                .hasSize(1);
    }

    @Test
    void a_host_given_by_ip_is_written_onto_the_entity_holding_that_ip() {
        String name = "zz-g10-by-ip";
        String ip = "192.0.2.77";
        known("zz-legacy-pg", "ip", ip);
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                "src/main/resources/application-prod.yml",
                                """
                                spring:
                                  datasource:
                                    url: jdbc:postgresql://%s:5432/ipdb
                                """
                                        .formatted(ip)));

        harness.scanner.scan(project);

        assertWrittenOnto("zz-legacy-pg", name, ip);
        assertThat(facts("zz-legacy-pg")).contains("engine=postgresql@prod/scan");
    }

    /** An entity the user told about, by an id the scanner would never derive from the host. */
    private void known(String id, String key, String value) {
        harness.writes.write(
                new WriteRequest(
                        Source.USER,
                        id,
                        "database",
                        List.of(new FactInput(key, value, PROD, null)),
                        List.of(),
                        null,
                        null,
                        KNOWN_AT));
    }

    private void assertWrittenOnto(String known, String project, String derivedId) {
        assertThat(harness.rows(ENTITY_IDS))
                .extracting(row -> row.get("id"))
                .as("no second entity for the same host")
                .doesNotContain(derivedId)
                .contains(known, project);
        assertThat(harness.rows(DEPENDS_ON, Map.of("id", project)))
                .extracting(row -> row.get("edge"))
                .containsExactly(known + "@" + PROD);
    }

    private List<String> facts(String id) {
        return harness.rows(ENTITY_FACTS, Map.of("id", id)).stream()
                .map(row -> (String) row.get("fact"))
                .toList();
    }
}
