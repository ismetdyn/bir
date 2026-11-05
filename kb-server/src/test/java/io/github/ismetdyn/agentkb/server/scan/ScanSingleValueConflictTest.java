package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decision (A), SPEC §7: one scan that reports several values for a single-valued key of one
 * entity in one env writes one of them, the first by repo-relative path then line, with a FROM edge
 * to every file that reports it, and opens one conflict Signal naming the others by file:line. A
 * rescan of the unchanged repository then adds and closes nothing; before, every rescan replaced
 * the value once per file, forever.
 */
class ScanSingleValueConflictTest {

    private static final String NAME = "zz-cp5-ns";
    // qa, test and uat are synonyms of test in seed/schema/facts.yaml#environments.
    private static final String KEPT = NAME + "-qa";
    private static final int NAMESPACE_LINE = 5;
    private static final String NAMESPACE_FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact {key: 'namespace'})"
                    + " RETURN f.value AS value, f.env AS env, f.valid_to AS validTo";
    private static final String FROM_FILES =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact {key: 'namespace', env: 'test'})"
                    + "-[r:FROM]->(s:SourceFile) RETURN s.path + ':' + r.line AS at";
    private static final String CONFLICTS =
            "MATCH (s:Signal {kind: 'conflict'}) RETURN s.key AS key, s.payload AS payload";
    private static final String IMAGES =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact {key: 'image'})"
                    + " WHERE f.valid_to IS NULL RETURN f.value AS value";

    @TempDir Path dir;

    private ScanHarness harness;
    private Path project;

    @BeforeEach
    void open() throws IOException {
        harness = ScanHarness.open(dir);
        Map<String, String> files = new LinkedHashMap<>();
        // Written in reverse, so neither write order nor "last wins" picks a.yaml by chance.
        files.put("k8s/e.yaml", manifest(NAME + "-prod", "e"));
        files.put("k8s/d.yaml", manifest(KEPT, "d"));
        files.put("k8s/c.yaml", manifest(NAME + "-uat", "c"));
        files.put("k8s/b.yaml", manifest(NAME + "-test", "b"));
        files.put("k8s/a.yaml", manifest(KEPT, "a"));
        project = ScanProjects.project(harness.root, NAME, files);
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void one_scan_writes_the_first_value_by_path_and_raises_one_conflict_for_the_others() {
        harness.scanner.scan(project);

        assertThat(namespaces())
                .as("one value per env; the others were never written, so none is closed")
                .containsExactlyInAnyOrder(
                        Map.of("value", KEPT, "env", "test"),
                        Map.of("value", NAME + "-prod", "env", "prod"));
        assertThat(harness.rows(NAMESPACE_FACTS, Map.of("id", NAME)))
                .allSatisfy(fact -> assertThat(fact.get("validTo")).isNull());
        assertThat(harness.rows(FROM_FILES, Map.of("id", NAME)))
                .extracting(row -> row.get("at"))
                .as("every file reporting the kept value backs it")
                .containsExactlyInAnyOrder(
                        "k8s/a.yaml:" + NAMESPACE_LINE, "k8s/d.yaml:" + NAMESPACE_LINE);
        assertThat(harness.rows(CONFLICTS))
                .as("one dispute for test; prod has a single value and none")
                .singleElement()
                .satisfies(
                        signal -> {
                            assertThat(signal.get("key")).isEqualTo(NAME + "/namespace/test");
                            assertThat(signal.get("payload").toString())
                                    .contains("k8s/b.yaml:" + NAMESPACE_LINE)
                                    .contains("k8s/c.yaml:" + NAMESPACE_LINE);
                        });
        assertThat(harness.rows(IMAGES, Map.of("id", NAME)))
                .as("a multi-valued key keeps every value")
                .hasSize(5);
    }

    @Test
    void a_rescan_of_the_unchanged_repository_adds_and_closes_nothing() {
        harness.scanner.scan(project);
        Map<String, Object> before = harness.state("observed_at", "updated_at", "count", "last_at");

        ScanResult second = harness.scanner.scan(project);
        ScanResult third = harness.scanner.scan(project);

        assertThat(List.of(second.added(), second.closed(), third.added(), third.closed()))
                .as("added and closed of the second and third scan")
                .containsOnly(0);
        assertThat(harness.state("observed_at", "updated_at", "count", "last_at"))
                .isEqualTo(before);
        assertThat(harness.rows(CONFLICTS)).hasSize(1);
    }

    private List<Map<String, Object>> namespaces() {
        return harness.rows(NAMESPACE_FACTS, Map.of("id", NAME)).stream()
                .map(row -> Map.of("value", row.get("value"), "env", row.get("env")))
                .toList();
    }

    /** A Deployment whose namespace is on line {@link #NAMESPACE_LINE}. */
    private static String manifest(String namespace, String image) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: zz-cp5-ns
                  namespace: %s
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          image: registry.ornek.example/zz/zz-cp5-ns-%s:1.0.0
                """
                .formatted(namespace, image);
    }
}
