package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decision (E), docs/SECRETS.md: a k8s container env {@code name: X, value: v} that looks like
 * a secret is a Secret, as in the Compose, Ansible, DotEnv and Spring extractors, with a finding at
 * the line of the value because the file is tracked. An ordinary env value and a secretKeyRef stay
 * what they were: no Secret, no fact (ScanDeploymentExtractorTest).
 */
class ScanK8sEnvSecretTest {

    private static final String MANIFEST = "k8s/app.yaml";
    private static final String NAME = "zz-cp5-k8s-pw";
    private static final String VALUE = "kbtest-fake-7d2a90c4e15b3f86";
    private static final int VALUE_LINE = 16;
    private static final String SECRETS =
            "MATCH (s:Secret) RETURN s.env AS env, s.secret_value AS value, s.source AS source";
    private static final String OPEN_FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                    + " OPTIONAL MATCH (f)-[r:FROM]->(s:SourceFile)"
                    + " RETURN f.key AS key, f.value AS value, s.path + ':' + r.line AS at";

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
    void a_plain_password_in_a_tracked_k8s_container_env_is_a_secret_with_a_finding() {
        Path project =
                ScanProjects.project(
                        harness.root,
                        NAME,
                        Map.of(
                                MANIFEST,
                                """
                                apiVersion: apps/v1
                                kind: Deployment
                                metadata:
                                  name: zz-cp5-k8s-pw
                                  namespace: zz-cp5-k8s-pw-prod
                                spec:
                                  template:
                                    spec:
                                      containers:
                                        - name: app
                                          image: registry.ornek.example/zz/zz-cp5-k8s-pw:1.0.0
                                          env:
                                            - name: LOG_LEVEL
                                              value: debug
                                            - name: DB_PASSWORD
                                              value: %s
                                            - name: API_TOKEN
                                              valueFrom:
                                                secretKeyRef:
                                                  name: zz-cp5-k8s-pw
                                                  key: token
                                """
                                        .formatted(VALUE)));

        ScanResult result = harness.scanner.scan(project);

        assertThat(harness.rows(SECRETS))
                .as("one Secret: neither LOG_LEVEL nor the secretKeyRef")
                .containsExactly(Map.of("env", "prod", "value", VALUE, "source", "scan"));
        assertThat(harness.rows(OPEN_FACTS, Map.of("id", NAME)))
                .as("the finding at the value's line; namespace and image still read")
                .anySatisfy(
                        fact -> {
                            assertThat(fact.get("key")).isEqualTo("finding");
                            assertThat(fact.get("at")).isEqualTo(MANIFEST + ":" + VALUE_LINE);
                        })
                .anySatisfy(fact -> assertThat(fact.get("key")).isEqualTo("namespace"))
                .anySatisfy(fact -> assertThat(fact.get("key")).isEqualTo("image"))
                .noneMatch(fact -> "debug".equals(fact.get("value")));
        assertThat(harness.state("secret_value").toString()).doesNotContain(VALUE);
        assertThat(result.toString()).doesNotContain(VALUE);
    }
}
