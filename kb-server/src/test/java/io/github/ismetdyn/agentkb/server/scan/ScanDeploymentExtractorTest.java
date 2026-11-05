package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Compose, K8s and Ansible extractors (ARCHITECTURE §6) on small projects beside the fixture:
 * the env comes from the k8s namespace suffix and the inventory group, both through the synonyms of
 * seed/schema/facts.yaml#environments, compose is local; a compose service is no entity and a
 * reference or secretKeyRef no secret (expected.yaml not_expected); a plain-text password in a
 * tracked deployment file is a Secret with a finding and nowhere else (docs/SECRETS.md, invariant
 * 2).
 */
class ScanDeploymentExtractorTest {

    private static final String FACTS =
            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                    + " OPTIONAL MATCH (f)-[r:FROM]->(s:SourceFile)"
                    + " RETURN f.key AS key, f.value AS value, f.env AS env, f.source AS source,"
                    + " s.path AS path, r.line AS line";
    private static final String COMPOSE = "docker-compose.yml";
    private static final String MANIFEST = "k8s/app.yaml";
    private static final String INVENTORY = "deploy/inventory.ini";

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
    void compose_services_become_no_entities_and_a_reference_no_secret() {
        String name = "zz-g11-compose";
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                COMPOSE,
                                """
                                services:
                                  oracle:
                                    image: gvenzl/oracle-free:23-slim
                                    ports:
                                      - "1521:1521"
                                    environment:
                                      ORACLE_PASSWORD: ${ORACLE_PASSWORD}
                                  app:
                                    build: .
                                    ports:
                                      - "8080:8080"
                                    depends_on:
                                      - oracle
                                """));

        harness.scanner.scan(project);

        assertThat(ids("MATCH (e:Entity) RETURN e.id AS id")).containsExactly(name);
        assertThat(ids("MATCH (s:Secret) RETURN s.id AS id")).isEmpty();
        assertThat(ids("MATCH (:Entity)-[r]->(:Entity) RETURN type(r) AS id")).isEmpty();
        assertThat(facts(name))
                .as("whatever compose adds to the project is local")
                .allSatisfy(fact -> assertThat(fact.env()).isEqualTo("local"));
    }

    @Test
    void a_plain_password_in_a_tracked_compose_file_is_a_local_secret_with_a_finding() {
        String name = "zz-g11-compose-pw";
        String value = "kbtest-fake-4c7e19a2d05b8f63";
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                COMPOSE,
                                """
                                services:
                                  db:
                                    image: postgres:16
                                    environment:
                                      POSTGRES_PASSWORD: %s
                                """
                                        .formatted(value)));

        ScanResult result = harness.scanner.scan(project);

        assertPlainTextSecret(result, name, value, "local", COMPOSE, 5);
    }

    @Test
    void k8s_records_namespace_image_and_ingress_url_in_the_env_of_the_namespace_suffix() {
        String name = "zz-g11-k8s";
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                MANIFEST,
                                """
                                apiVersion: apps/v1
                                kind: Deployment
                                metadata:
                                  name: zz-g11-k8s
                                  namespace: zz-g11-k8s-qa
                                spec:
                                  template:
                                    spec:
                                      containers:
                                        - name: app
                                          image: registry.ornek.example/zz/zz-g11-k8s:2.0.1
                                          env:
                                            - name: DB_PASSWORD
                                              valueFrom:
                                                secretKeyRef:
                                                  name: zz-g11-k8s-db
                                                  key: password
                                ---
                                apiVersion: networking.k8s.io/v1
                                kind: Ingress
                                metadata:
                                  name: zz-g11-k8s
                                  namespace: zz-g11-k8s-qa
                                spec:
                                  rules:
                                    - host: zz-g11-k8s-qa.ornek.example
                                """));

        harness.scanner.scan(project);

        // qa is a synonym of test; the namespace is recorded once, from its first line.
        assertThat(facts(name))
                .containsExactlyInAnyOrder(
                        Seen.scanned("namespace", "zz-g11-k8s-qa", "test", MANIFEST, 5),
                        Seen.scanned(
                                "image",
                                "registry.ornek.example/zz/zz-g11-k8s:2.0.1",
                                "test",
                                MANIFEST,
                                11),
                        Seen.scanned(
                                "url",
                                "https://zz-g11-k8s-qa.ornek.example",
                                "test",
                                MANIFEST,
                                26));
        assertThat(ids("MATCH (s:Secret) RETURN s.id AS id"))
                .as("a secretKeyRef holds no value")
                .isEmpty();
        assertThat(ids("MATCH (e:Entity) RETURN e.id AS id"))
                .as("neither the ingress host nor the referenced k8s Secret is an entity")
                .containsExactly(name);
    }

    @Test
    void ansible_hosts_become_servers_with_the_ip_in_the_env_of_their_group() {
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-g11-ansible",
                        Map.of(
                                INVENTORY,
                                """
                                [prod]
                                zz-g11-app-01 ansible_host=192.0.2.31 ansible_user=deploy

                                [qa]
                                zz-g11-app-qa-01 ansible_host=198.51.100.31
                                """));

        harness.scanner.scan(project);

        assertThat(facts("zz-g11-app-01"))
                .containsExactly(Seen.scanned("ip", "192.0.2.31", "prod", INVENTORY, 2));
        assertThat(facts("zz-g11-app-qa-01"))
                .containsExactly(Seen.scanned("ip", "198.51.100.31", "test", INVENTORY, 5));
        assertThat(
                        harness.rows(
                                "MATCH (e:Entity) WHERE e.id STARTS WITH 'zz-g11-app'"
                                        + " RETURN labels(e) AS labels, e.status AS status"))
                .hasSize(2)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("labels"))
                                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                                    .contains("Server");
                            assertThat(row.get("status")).isEqualTo("observed");
                        });
    }

    @Test
    void a_plain_password_in_a_tracked_inventory_is_a_secret_with_a_finding() {
        String name = "zz-g11-ansible-pw";
        String value = "kbtest-fake-b2190e6d7f43ac58";
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                INVENTORY,
                                """
                                [prod]
                                zz-g11-app-02 ansible_host=192.0.2.32 ansible_password=%s
                                """
                                        .formatted(value)));

        ScanResult result = harness.scanner.scan(project);

        assertPlainTextSecret(result, name, value, "prod", INVENTORY, 2);
        assertThat(facts("zz-g11-app-02"))
                .as("the host is still read")
                .containsExactly(Seen.scanned("ip", "192.0.2.32", "prod", INVENTORY, 2));
    }

    /**
     * docs/SECRETS.md: the value lands in one Secret's secret_value; the tracked file opens one
     * finding at its line; the value is in no other property and not in the ScanReport.
     */
    private void assertPlainTextSecret(
            ScanResult result, String project, String value, String env, String file, int line) {
        assertThat(
                        harness.rows(
                                "MATCH (s:Secret) RETURN s.env AS env, s.secret_value AS value,"
                                        + " s.source AS source"))
                .containsExactly(Map.of("env", env, "value", value, "source", "scan"));
        assertThat(facts(project))
                .filteredOn(fact -> fact.key().equals("finding"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.value()).contains(file + ":" + line);
                            assertThat(finding.env()).isEqualTo(env);
                            assertThat(finding.path()).isEqualTo(file);
                            assertThat(finding.line()).isEqualTo(line);
                        });
        assertThat(harness.state("secret_value").toString()).doesNotContain(value);
        assertThat(result.toString()).doesNotContain(value);
    }

    private List<Object> ids(String cypher) {
        return harness.rows(cypher).stream().map(row -> row.get("id")).toList();
    }

    private List<Seen> facts(String entity) {
        return harness.rows(FACTS, Map.of("entity", entity)).stream()
                .map(
                        row ->
                                new Seen(
                                        (String) row.get("key"),
                                        (String) row.get("value"),
                                        (String) row.get("env"),
                                        (String) row.get("source"),
                                        (String) row.get("path"),
                                        row.get("line") instanceof Number line
                                                ? line.intValue()
                                                : -1))
                .toList();
    }

    /** An open fact with the path and line of its FROM edge (-1 without one). */
    private record Seen(
            String key, String value, String env, String source, String path, int line) {

        static Seen scanned(String key, String value, String env, String path, int line) {
            return new Seen(key, value, env, "scan", path, line);
        }
    }
}
