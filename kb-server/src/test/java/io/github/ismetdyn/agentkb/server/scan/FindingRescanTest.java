package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/SECRETS.md, SPEC §7 rules 4 and 5, CLAUDE.md invariant 5: every plain-text password line of
 * a tracked file keeps its own finding (finding is multi-valued), and a rescan closes exactly the
 * findings no file backs any more: the password moved to an environment variable, the file was
 * deleted, or the password was the file's only extracted item. Findings are closed, never deleted.
 */
class FindingRescanTest {

    private static final String NAME = "zz-bulgu";
    private static final String DATASOURCE_FILE = "src/main/resources/application-prod.yml";
    private static final String MAIL_PROD_FILE = "src/main/resources/bootstrap-prod.yml";
    private static final String MAIL_TEST_FILE = "src/main/resources/bootstrap-test.yml";
    private static final int DATASOURCE_PASSWORD_LINE = 4;
    private static final int MAIL_PASSWORD_LINE = 3;
    private static final String DB_VALUE = "kbtest-fake-41c7e09b2d5a8f36";
    private static final String MAIL_VALUE = "kbtest-fake-9d03b6e1a74c25f8";
    private static final String FINDINGS =
            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact {key: 'finding'})"
                    + " RETURN f.value AS value, f.valid_to AS validTo";

    @TempDir Path dir;

    private ScanHarness harness;

    @BeforeEach
    void openGraph() throws IOException {
        harness = ScanHarness.open(dir);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void two_passwords_of_one_env_in_two_files_keep_two_findings_and_fixing_one_closes_only_it()
            throws IOException {
        Path project = project(MAIL_PROD_FILE);
        harness.scanner.scan(project);
        String datasourceFinding = finding(DATASOURCE_FILE, DATASOURCE_PASSWORD_LINE);
        String mailFinding = finding(MAIL_PROD_FILE, MAIL_PASSWORD_LINE);
        assertThat(findings(true))
                .as("each plain-text password line keeps its own finding, both in prod")
                .containsExactlyInAnyOrder(datasourceFinding, mailFinding);

        replace(project, DATASOURCE_FILE, DB_VALUE, "${DB_PASSWORD}");
        harness.scanner.scan(project);

        assertThat(findings(true))
                .as("the other file still has its password, and no other finding opened")
                .containsExactly(mailFinding);
        assertThat(findings(false)).as("closed, not deleted").containsExactly(datasourceFinding);
    }

    /**
     * Committed or not: git still lists a file removed from the working copy until the deletion is
     * staged, and the finding closes all the same.
     */
    @ParameterizedTest(name = "committed: {0}")
    @ValueSource(booleans = {true, false})
    void deleting_the_tracked_file_closes_its_finding(boolean committed) throws IOException {
        Path project = project(MAIL_TEST_FILE);
        harness.scanner.scan(project);
        String mailFinding = finding(MAIL_TEST_FILE, MAIL_PASSWORD_LINE);
        assertThat(findings(true)).as("precondition").contains(mailFinding);

        Files.delete(project.resolve(MAIL_TEST_FILE));
        if (committed) {
            ScanProjects.commitAll(project, "drop the mail config");
        }
        harness.scanner.scan(project);

        assertThat(findings(false)).containsExactly(mailFinding);
        assertThat(findings(true))
                .as("the finding of the file that is still there stays")
                .containsExactly(finding(DATASOURCE_FILE, DATASOURCE_PASSWORD_LINE));
    }

    /**
     * A weak password can be part of the finding text (here of the file name): the finding names
     * the file and line, never the value, so it opens all the same and is kept on a rescan.
     */
    @Test
    void a_password_that_is_part_of_the_finding_text_keeps_its_finding() throws IOException {
        String weak = "yml";
        Path project = project(MAIL_TEST_FILE);
        replace(project, DATASOURCE_FILE, DB_VALUE, weak);
        harness.scanner.scan(project);

        assertThat(findings(true))
                .containsExactlyInAnyOrder(
                        finding(DATASOURCE_FILE, DATASOURCE_PASSWORD_LINE),
                        finding(MAIL_TEST_FILE, MAIL_PASSWORD_LINE));
        harness.scanner.scan(project);
        assertThat(findings(false)).as("a rescan closes neither").isEmpty();
    }

    @Test
    void a_file_whose_only_item_was_the_password_closes_its_finding_once_it_is_a_reference()
            throws IOException {
        Path project = project(MAIL_TEST_FILE);
        harness.scanner.scan(project);
        String mailFinding = finding(MAIL_TEST_FILE, MAIL_PASSWORD_LINE);
        assertThat(findings(true)).as("precondition").contains(mailFinding);

        replace(project, MAIL_TEST_FILE, MAIL_VALUE, "${MAIL_PASSWORD}");
        harness.scanner.scan(project);

        assertThat(findings(false)).containsExactly(mailFinding);
        assertThat(findings(true))
                .as("the finding of the other file stays")
                .containsExactly(finding(DATASOURCE_FILE, DATASOURCE_PASSWORD_LINE));
    }

    /**
     * A prod datasource with its password (line 4) and {@code mailFile}, a bootstrap config whose
     * only extracted item is its password (line 3); both files are tracked, so both open a finding.
     */
    private Path project(String mailFile) {
        return ScanProjects.project(
                harness.root,
                NAME,
                Map.of(
                        DATASOURCE_FILE,
                        """
                        spring:
                          datasource:
                            url: jdbc:oracle:thin:@zz-bulgu-db.ornek.example:1521/ZZPDB
                            password: %s
                        """
                                .formatted(DB_VALUE),
                        mailFile,
                        """
                        app:
                          mail:
                            password: %s
                        """
                                .formatted(MAIL_VALUE)));
    }

    /** The finding text of decision (1); it names the file and line, never the value. */
    private static String finding(String path, int line) {
        return "Düz metin parola bulundu: " + path + ":" + line + "; ortam değişkenine taşınmalı";
    }

    private static void replace(Path project, String path, String value, String reference)
            throws IOException {
        Path file = project.resolve(path);
        String text = Files.readString(file);
        assertThat(text).contains(value);
        Files.writeString(file, text.replace(value, reference));
        ScanProjects.commitAll(project, "move a password to the environment");
    }

    /** The values of the project's open findings, or of its closed ones. */
    private List<Object> findings(boolean isOpen) {
        return harness.rows(FINDINGS, Map.of("entity", NAME)).stream()
                .filter(f -> (f.get("validTo") == null) == isOpen)
                .map(f -> f.get("value"))
                .toList();
    }
}
