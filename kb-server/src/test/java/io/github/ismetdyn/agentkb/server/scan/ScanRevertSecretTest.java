package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismetdyn.agentkb.server.snapshot.CheckReport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckService;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A value the user sets with {@code PUT /api/v1/secrets/<id>} makes that Secret user-owned (lead
 * decision 1, SPEC §7 rule 9): its source becomes user and its batch the PUT's own. So {@code kb
 * revert --batch} of the scan that first opened it deletes the scan's Secrets with their values but
 * keeps the user-owned one with its value (only the scan's HAS_SECRET edge goes, and kb check lists
 * it as a local record), and a rescan never overwrites the user's value: it opens a conflict signal
 * that names the id only. Secret values are the documented history-less exception (rule 9).
 */
class ScanRevertSecretTest {

    private static final String VALUES =
            "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN s.id AS id ORDER BY id";
    private static final String SECRET =
            "MATCH (s:Secret {id: $id}) RETURN s.secret_value AS value, s.source AS source,"
                    + " s.batch AS batch";
    private static final String HOLDERS =
            "MATCH (e)-[r:HAS_SECRET]->(:Secret {id: $id}) RETURN r.batch AS batch";
    private static final String BY_VALUE =
            "MATCH (s:Secret) WHERE s.secret_value = $value RETURN s.id AS id";
    private static final String CONFLICT_SIGNALS =
            "MATCH (s:Signal {kind: 'conflict', key: $id}) RETURN s.payload AS payload";
    private static final String PROD_PASSWORD = "kbtest-fake-prod-5d1a8c3e";
    private static final String LOCAL_PASSWORD = "kbtest-fake-local-9b2f04e7";
    private static final String USER_VALUE = "kbtest-fake-user-set-7e3c61a0";
    private static final String RESCANNED_PASSWORD = "kbtest-fake-prod-rotated-4a90d2b1";
    private static final String PROD_CONFIG = "src/main/resources/application-prod.yml";

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
    void a_put_makes_a_scanned_secret_user_owned_in_a_batch_of_its_own() {
        ScanResult scan = scanProject();
        String id = secretHolding(PROD_PASSWORD);

        harness.writes.putSecret(id, USER_VALUE);

        Map<String, Object> secret = secret(id);
        assertThat(secret.get("value")).isEqualTo(USER_VALUE);
        assertThat(secret.get("source")).isEqualTo("user");
        assertThat(secret.get("batch")).isNotNull().isNotEqualTo(scan.batch());
    }

    @Test
    void reverting_a_scan_keeps_a_secret_the_user_set_later_and_deletes_the_others() {
        ScanResult scan = scanProject();
        List<Object> secrets = harness.rows(VALUES).stream().map(row -> row.get("id")).toList();
        assertThat(secrets).as("precondition: the scan stored two values").hasSize(2);
        String userOwned = secretHolding(PROD_PASSWORD);
        harness.writes.putSecret(userOwned, USER_VALUE);

        RevertResult result = harness.writes.maintenance().revert(scan.batch());

        assertThat(harness.rows(VALUES)).containsExactly(Map.of("id", userOwned));
        assertThat(secret(userOwned).get("value")).isEqualTo(USER_VALUE);
        assertThat(harness.rows(HOLDERS, Map.of("id", userOwned)))
                .as("the scan's HAS_SECRET edge goes with the scan")
                .isEmpty();
        assertThat(result.deletedSecretValues())
                .as("only the scan-owned value is deleted")
                .isEqualTo(secrets.size() - 1);
        assertThat(new CheckService(harness.store, "").check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .contains(tuple("local-record", userOwned));
    }

    @Test
    void a_rescan_never_overwrites_a_value_the_user_set_and_raises_a_conflict_instead()
            throws IOException {
        Path project =
                ScanProjects.springProject(
                        harness.root, "zz-revert", PROD_PASSWORD, LOCAL_PASSWORD);
        harness.scanner.scan(project);
        String id = secretHolding(PROD_PASSWORD);
        harness.writes.putSecret(id, USER_VALUE);
        Path config = project.resolve(PROD_CONFIG);
        Files.writeString(
                config, Files.readString(config).replace(PROD_PASSWORD, RESCANNED_PASSWORD));
        ScanProjects.commitAll(project, "rotate the prod password");

        harness.scanner.scan(project);

        assertThat(secret(id).get("value")).isEqualTo(USER_VALUE);
        assertThat(secret(id).get("source")).isEqualTo("user");
        List<Map<String, Object>> signals = harness.rows(CONFLICT_SIGNALS, Map.of("id", id));
        assertThat(signals).as("a conflict signal names the secret id").hasSize(1);
        assertThat(signals.toString())
                .doesNotContain(USER_VALUE)
                .doesNotContain(RESCANNED_PASSWORD)
                .doesNotContain(PROD_PASSWORD);
    }

    private ScanResult scanProject() {
        return harness.scanner.scan(
                ScanProjects.springProject(
                        harness.root, "zz-revert", PROD_PASSWORD, LOCAL_PASSWORD));
    }

    private String secretHolding(String value) {
        List<Map<String, Object>> rows = harness.rows(BY_VALUE, Map.of("value", value));
        assertThat(rows).as("precondition: one Secret holds the scanned value").hasSize(1);
        return (String) rows.getFirst().get("id");
    }

    private Map<String, Object> secret(String id) {
        List<Map<String, Object>> rows = harness.rows(SECRET, Map.of("id", id));
        assertThat(rows).as("Secret %s", id).hasSize(1);
        return rows.getFirst();
    }
}
