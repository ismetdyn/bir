package io.github.ismetdyn.agentkb.server.scan;

import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.PROJECT;
import static io.github.ismetdyn.agentkb.server.scan.ScanProjects.LOCAL_PASSWORD;
import static io.github.ismetdyn.agentkb.server.scan.ScanProjects.PROD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The not_expected part of fixtures/scanner/expected.yaml and docs/SECRETS.md after one scan of the
 * fixture into an empty graph: a reference holds no value, a finding only for a tracked file, the
 * fake values nowhere but in secret_value, nothing but source scan.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanNotExpectedTest {

    private static final String SCAN = "scan";

    // Static: under PER_CLASS an instance @TempDir is not injected before @BeforeAll.
    @TempDir static Path dir;

    private ScanHarness harness;

    @BeforeAll
    void scanTheFixture() throws IOException {
        harness = ScanHarness.open(dir);
        harness.scanner.scan(ScanProjects.copyFixture(harness.root));
    }

    @AfterAll
    void close() {
        harness.close();
    }

    /** ${DB_PASSWORD}, the compose ${ORACLE_PASSWORD} and the k8s secretKeyRef hold no value. */
    @Test
    void only_the_two_secrets_with_a_value_in_a_file_exist() {
        assertThat(harness.rows("MATCH (s:Secret) RETURN s.id AS id"))
                .extracting(row -> row.get("id"))
                .containsExactlyInAnyOrderElementsOf(
                        ExpectedScan.EXPECTED
                                .at("/project/secrets")
                                .valueStream()
                                .map(secret -> secret.get("id").asString())
                                .toList());
    }

    @Test
    void the_placeholder_reference_in_the_test_profile_becomes_no_secret_and_no_fact() {
        assertThat(harness.rows("MATCH (e:Entity {id: 'ornek-db-test'}) RETURN e.id AS id"))
                .as("the test profile was read")
                .isNotEmpty();
        assertThat(harness.rows("MATCH (s:Secret {env: 'test'}) RETURN s.id AS id")).isEmpty();
        assertThat(
                        harness.rows(
                                "MATCH (f:Fact) WHERE f.value CONTAINS '${' RETURN f.value AS"
                                        + " value"))
                .isEmpty();
    }

    /** docs/SECRETS.md: a finding only for a tracked file; .env is git-ignored in the fixture. */
    @Test
    void a_plain_text_finding_opens_for_the_tracked_file_only() {
        List<Object> findings =
                harness.rows("MATCH (f:Fact {key: 'finding'}) RETURN f.value AS value").stream()
                        .map(row -> row.get("value"))
                        .toList();

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().toString())
                .contains("application-prod.yml:10")
                .doesNotContain(".env");
    }

    @Test
    void the_fake_values_are_nowhere_in_the_graph_but_in_secret_value() {
        String graph = harness.state("secret_value").toString();

        assertThat(graph).contains(PROJECT).doesNotContain(PROD_PASSWORD, LOCAL_PASSWORD);
    }

    @Test
    void every_fact_secret_and_relation_written_has_the_scan_source() {
        assertThat(harness.rows("MATCH (f:Fact) RETURN DISTINCT f.source AS source"))
                .extracting(row -> row.get("source"))
                .containsExactly(SCAN);
        assertThat(harness.rows("MATCH (s:Secret) RETURN DISTINCT s.source AS source"))
                .extracting(row -> row.get("source"))
                .containsExactly(SCAN);
        assertThat(
                        harness.rows(
                                "MATCH ()-[r]->() WHERE r.source IS NOT NULL"
                                        + " RETURN DISTINCT r.source AS source"))
                .extracting(row -> row.get("source"))
                .containsExactly(SCAN);
    }
}
