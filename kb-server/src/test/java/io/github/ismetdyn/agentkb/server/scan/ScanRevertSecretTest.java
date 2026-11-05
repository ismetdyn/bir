package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb revert --batch} of a scan (invariant 5, ADR-0022 decisions 22-23): the Secrets the scan
 * opened are deleted with whatever value they hold now, also one the user set later with {@code kb
 * secret set}. The value lives only on this machine, so it is gone; the result counts it so the CLI
 * can say so (docs/CLIENTS.md).
 */
class ScanRevertSecretTest {

    private static final String VALUES =
            "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN s.id AS id ORDER BY id";

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
    void reverting_a_scan_deletes_its_secrets_and_counts_their_values() {
        Path project =
                ScanProjects.springProject(
                        harness.root,
                        "zz-revert",
                        "kbtest-fake-prod-5d1a8c3e",
                        "kbtest-fake-local-9b2f04e7");
        ScanResult scan = harness.scanner.scan(project);
        List<Object> secrets =
                harness.rows(VALUES, Map.of()).stream().map(row -> row.get("id")).toList();
        assertThat(secrets).as("precondition: the scan stored values").isNotEmpty();
        harness.writes.putSecret((String) secrets.getFirst(), "kbtest-fake-user-set-7e3c61a0");

        RevertResult result = harness.writes.revert(scan.batch());

        assertThat(harness.rows(VALUES, Map.of())).isEmpty();
        assertThat(result.deletedSecretValues()).isEqualTo(secrets.size());
    }
}
