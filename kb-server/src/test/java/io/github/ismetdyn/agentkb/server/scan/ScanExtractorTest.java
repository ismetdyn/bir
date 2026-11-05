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

/**
 * The SpringConfig and BuildFile forms the fixture does not have (ARCHITECTURE §6): a .properties
 * profile whose name is an environment synonym (seed/schema/facts.yaml#environments), a {@code
 * ${...}} reference there, a Gradle build, and a config file that is a symlink out of the allowed
 * roots (invariant 9).
 */
class ScanExtractorTest {

    private static final String FACTS =
            "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                    + " OPTIONAL MATCH (f)-[r:FROM]->(s:SourceFile)"
                    + " RETURN f.key AS key, f.value AS value, f.env AS env, s.path AS path,"
                    + " r.line AS line";
    private static final String MULTI_YML = "src/main/resources/application.yml";

    @TempDir Path dir;
    @TempDir Path outside;

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
    void a_properties_profile_named_by_a_synonym_writes_its_datasource_to_that_env() {
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-g10-props",
                        Map.of(
                                "src/main/resources/application-production.properties",
                                """
                                spring.application.name=zz-g10-props
                                spring.datasource.url=jdbc:postgresql://zz-g10-pg.ornek.example:5432/zzdb
                                spring.datasource.password=${DB_PASSWORD}
                                """));

        harness.scanner.scan(project);

        String file = "src/main/resources/application-production.properties";
        assertThat(facts("zz-g10-pg"))
                .containsExactlyInAnyOrder(
                        fact("engine", "postgresql", "prod", file, 2),
                        fact("hostname", "zz-g10-pg.ornek.example", "prod", file, 2),
                        fact("port", "5432", "prod", file, 2),
                        fact("db_name", "zzdb", "prod", file, 2));
        assertThat(harness.rows("MATCH (s:Secret) RETURN s.id AS id"))
                .as("${DB_PASSWORD} holds no value")
                .isEmpty();
        assertThat(harness.rows("MATCH (f:Fact {key: 'finding'}) RETURN f.value AS value"))
                .as("a reference is no plain-text secret")
                .isEmpty();
    }

    /**
     * One application.yml with {@code ---} sections: a section with {@code
     * spring.config.activate.on-profile} belongs to that profile's env, the section without one to
     * default. Each datasource, relation, secret and finding lands in the env of its own section.
     */
    @Test
    void each_section_of_a_multi_document_yaml_maps_to_the_env_of_its_profile() {
        String name = "zz-g10-multi";
        String prodValue = "kbtest-fake-3b8e1c5d7a0f9246";
        Path project =
                ScanProjects.project(
                        harness.root,
                        name,
                        Map.of(
                                MULTI_YML,
                                """
                                spring:
                                  application:
                                    name: zz-g10-multi
                                  datasource:
                                    url: jdbc:postgresql://zz-g10-dev-pg.ornek.example:5432/devdb
                                ---
                                spring:
                                  config:
                                    activate:
                                      on-profile: prod
                                  datasource:
                                    url: jdbc:postgresql://zz-g10-prod-pg.ornek.example:5432/proddb
                                    password: %s
                                ---
                                spring:
                                  config:
                                    activate:
                                      on-profile: test
                                  datasource:
                                    url: jdbc:postgresql://zz-g10-test-pg.ornek.example:5433/testdb
                                """
                                        .formatted(prodValue)));

        harness.scanner.scan(project);

        assertThat(facts("zz-g10-dev-pg"))
                .containsExactlyInAnyOrder(
                        fact("engine", "postgresql", "default", MULTI_YML, 5),
                        fact("hostname", "zz-g10-dev-pg.ornek.example", "default", MULTI_YML, 5),
                        fact("port", "5432", "default", MULTI_YML, 5),
                        fact("db_name", "devdb", "default", MULTI_YML, 5));
        assertThat(facts("zz-g10-prod-pg"))
                .containsExactlyInAnyOrder(
                        fact("engine", "postgresql", "prod", MULTI_YML, 12),
                        fact("hostname", "zz-g10-prod-pg.ornek.example", "prod", MULTI_YML, 12),
                        fact("port", "5432", "prod", MULTI_YML, 12),
                        fact("db_name", "proddb", "prod", MULTI_YML, 12));
        assertThat(facts("zz-g10-test-pg"))
                .containsExactlyInAnyOrder(
                        fact("engine", "postgresql", "test", MULTI_YML, 20),
                        fact("hostname", "zz-g10-test-pg.ornek.example", "test", MULTI_YML, 20),
                        fact("port", "5433", "test", MULTI_YML, 20),
                        fact("db_name", "testdb", "test", MULTI_YML, 20));
        assertThat(
                        harness.rows(
                                "MATCH (:Entity {id: $id})-[r:DEPENDS_ON]->(t:Entity)"
                                        + " RETURN t.id + '@' + r.env AS edge",
                                Map.of("id", name)))
                .extracting(row -> row.get("edge"))
                .containsExactlyInAnyOrder(
                        "zz-g10-dev-pg@default", "zz-g10-prod-pg@prod", "zz-g10-test-pg@test");
        assertThat(harness.rows("MATCH (s:Secret) RETURN s.id AS id"))
                .extracting(row -> row.get("id"))
                .containsExactly(name + "/prod/spring.datasource.password");
        assertThat(facts(name))
                .filteredOn(f -> f.key().equals("finding"))
                .singleElement()
                .satisfies(f -> assertThat(f.env()).isEqualTo("prod"))
                .satisfies(f -> assertThat(f.line()).isEqualTo(13))
                .satisfies(f -> assertThat(f.value()).doesNotContain(prodValue));
    }

    @Test
    void a_gradle_build_gives_the_stack_and_the_spring_boot_and_java_versions() {
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-g10-gradle",
                        Map.of(
                                "settings.gradle",
                                "rootProject.name = 'zz-g10-gradle'\n",
                                "build.gradle",
                                """
                                plugins {
                                \tid 'java'
                                \tid 'org.springframework.boot' version '3.5.6'
                                \tid 'io.spring.dependency-management' version '1.1.7'
                                }

                                group = 'com.example'
                                version = '0.0.1-SNAPSHOT'

                                java {
                                \ttoolchain {
                                \t\tlanguageVersion = JavaLanguageVersion.of(21)
                                \t}
                                }
                                """));

        harness.scanner.scan(project);

        assertThat(facts("zz-g10-gradle"))
                .contains(
                        fact("stack", "java-spring", "default", "build.gradle", 3),
                        fact(
                                "framework_version",
                                "spring-boot 3.5.6",
                                "default",
                                "build.gradle",
                                3),
                        fact("java_version", "21", "default", "build.gradle", 12));
    }

    @Test
    void a_config_file_linked_to_outside_the_allowed_roots_is_not_read() throws IOException {
        Path secretFile = outside.resolve("application-prod.yml");
        Files.writeString(
                secretFile,
                """
                spring:
                  datasource:
                    url: jdbc:oracle:thin:@zz-g10-outside.ornek.example:1521/OUT
                """);
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-g10-link",
                        Map.of(
                                "src/main/resources/application-test.yml",
                                """
                                spring:
                                  datasource:
                                    url: jdbc:oracle:thin:@zz-g10-inside.ornek.example:1521/IN
                                """));
        Files.createSymbolicLink(
                project.resolve("src/main/resources/application-prod.yml"), secretFile);

        harness.scanner.scan(project);

        assertThat(harness.rows("MATCH (e:Entity) RETURN e.id AS id"))
                .extracting(row -> row.get("id"))
                .as("the file inside is scanned, the linked one is not")
                .contains("zz-g10-inside")
                .doesNotContain("zz-g10-outside");
    }

    private List<Seen> facts(String entity) {
        return harness.rows(FACTS, Map.of("entity", entity)).stream()
                .map(
                        row ->
                                new Seen(
                                        (String) row.get("key"),
                                        (String) row.get("value"),
                                        (String) row.get("env"),
                                        (String) row.get("path"),
                                        row.get("line") instanceof Number line
                                                ? line.intValue()
                                                : -1))
                .toList();
    }

    private static Seen fact(String key, String value, String env, String path, int line) {
        return new Seen(key, value, env, path, line);
    }

    /** An open fact with the path and line of its FROM edge (-1 without one). */
    private record Seen(String key, String value, String env, String path, int line) {}
}
