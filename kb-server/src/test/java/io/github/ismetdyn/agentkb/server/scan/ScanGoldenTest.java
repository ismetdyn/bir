package io.github.ismetdyn.agentkb.server.scan;

import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.DEFAULT_ENV;
import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.PROJECT;
import static io.github.ismetdyn.agentkb.server.scan.ExpectedScan.REPO;
import static io.github.ismetdyn.agentkb.server.scan.ScanProjects.LOCAL_PASSWORD;
import static io.github.ismetdyn.agentkb.server.scan.ScanProjects.PROD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * fixtures/scanner/expected.yaml ({@link ExpectedScan}) after one scan of the fixture into an empty
 * graph: every record open, in its env, from its file and line, with source scan; and nothing else
 * (no compose service entity, no k8s service port, no default port on the prod server).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanGoldenTest {

    private static final Path TYPES = Path.of("..", "seed", "schema", "types.yaml");
    private static final String SCAN = "scan";
    // Which fixture file holds which fake value.
    private static final Map<String, String> VALUE_IN =
            Map.of(
                    "src/main/resources/application-prod.yml",
                    PROD_PASSWORD,
                    ".env",
                    LOCAL_PASSWORD);

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

    @Test
    void the_repo_comes_from_the_git_remote_and_its_files_lie_in_it() {
        assertThat(
                        harness.rows(
                                "MATCH (p:Entity {id: $project})-[:HAS_REPO]->(r:Repo {id: $repo})"
                                        + " RETURN r.remote AS remote",
                                Map.of("project", PROJECT, "repo", REPO)))
                .extracting(row -> row.get("remote"))
                .containsExactly(ExpectedScan.EXPECTED.at("/repo/remote").asString());
        List<Map<String, Object>> files =
                harness.rows(
                        "MATCH (s:SourceFile) OPTIONAL MATCH (s)-[:IN]->(r:Repo)"
                                + " RETURN s.id AS id, s.path AS path, s.repo AS repo, r.id AS in");
        assertThat(files).isNotEmpty();
        assertThat(files)
                .allSatisfy(
                        file -> {
                            assertThat(file.get("in")).as(file.toString()).isEqualTo(REPO);
                            assertThat(file.get("repo")).as(file.toString()).isEqualTo(REPO);
                            assertThat(file.get("id"))
                                    .as(file.toString())
                                    .isEqualTo(REPO + ":" + file.get("path"));
                        });
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("io.github.ismetdyn.agentkb.server.scan.ExpectedScan#facts")
    void an_expected_fact_is_open_with_its_env_and_file_line(String entity, JsonNode fact) {
        String key = fact.get("key").asString();
        String env = fact.path("env").asString(DEFAULT_ENV);
        List<Map<String, Object>> open =
                harness.rows(
                        "MATCH (:Entity {id: $entity})-[:HAS_FACT]->(f:Fact {key: $key, env: $env})"
                                + " WHERE f.valid_to IS NULL"
                                + " OPTIONAL MATCH (f)-[r:FROM]->(s:SourceFile)"
                                + " OPTIONAL MATCH (f)-[:TARGET]->(t:Entity)"
                                + " RETURN f.value AS value, f.source AS source, s.id AS file,"
                                + " r.line AS line, t.id AS target",
                        Map.of("entity", entity, "key", key, "env", env));

        List<Map<String, Object>> matching =
                open.stream()
                        .filter(
                                row ->
                                        fact.has("value")
                                                ? fact.get("value")
                                                        .asString()
                                                        .equals(row.get("value"))
                                                : String.valueOf(row.get("value"))
                                                        .contains(fact.get("contains").asString()))
                        .toList();
        assertThat(matching).as("open %s.%s @%s: %s", entity, key, env, open).hasSize(1);
        assertThat(matching.getFirst().get("source")).isEqualTo(SCAN);
        assertThat(matching.getFirst().get("target"))
                .as("TARGET of %s.%s @%s", entity, key, env)
                .isEqualTo(fact.has("target") ? fact.get("target").asString() : null);
        assertSourced(matching.getFirst(), fact.get("from").asString());
    }

    /**
     * Nothing beyond expected.yaml: each open fact in (entity, key, env, target, from); every
     * relation starts at the project, none links two scanned entities; the entities are the project
     * and the expected ones (a compose service or an ingress host is none).
     */
    @Test
    void the_open_facts_relations_and_entities_are_exactly_the_expected_ones() {
        List<String> facts =
                harness
                        .rows(
                                "MATCH (e:Entity)-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                                        + " OPTIONAL MATCH (f)-[r:FROM]->(s:SourceFile)"
                                        + " OPTIONAL MATCH (f)-[:TARGET]->(t:Entity)"
                                        + " RETURN e.id AS entity, f.key AS key, f.env AS env,"
                                        + " t.id AS target, s.path AS path, r.line AS line")
                        .stream()
                        .map(
                                row ->
                                        ExpectedScan.shape(
                                                (String) row.get("entity"),
                                                (String) row.get("key"),
                                                (String) row.get("env"),
                                                (String) row.get("target"),
                                                row.get("path") + ":" + row.get("line")))
                        .toList();
        List<String> relations =
                ExpectedScan.relations()
                        .map(
                                args ->
                                        PROJECT
                                                + " "
                                                + args.get()[0]
                                                + " "
                                                + args.get()[1]
                                                + "@"
                                                + args.get()[2])
                        .toList();
        List<Object> entities =
                new ArrayList<>(ExpectedScan.entities().map(args -> args.get()[0]).toList());
        entities.add(PROJECT);

        SoftAssertions.assertSoftly(
                softly -> {
                    softly.assertThat(facts)
                            .as("open facts")
                            .containsExactlyInAnyOrderElementsOf(ExpectedScan.factShapes());
                    softly.assertThat(
                                    harness.rows(
                                            "MATCH (a:Entity)-[r]->(b:Entity) WHERE r.valid_to IS"
                                                    + " NULL RETURN a.id + ' ' + type(r) + ' ' +"
                                                    + " b.id + '@' + coalesce(r.env, 'default') AS"
                                                    + " edge"))
                            .as("open relations")
                            .extracting(row -> row.get("edge"))
                            .containsExactlyInAnyOrderElementsOf(relations);
                    softly.assertThat(harness.rows("MATCH (e:Entity) RETURN e.id AS id"))
                            .as("entities")
                            .extracting(row -> row.get("id"))
                            .containsExactlyInAnyOrderElementsOf(entities);
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.ismetdyn.agentkb.server.scan.ExpectedScan#secrets")
    void an_expected_secret_holds_the_value_of_its_file(String id, JsonNode secret) {
        String from = secret.get("from").asString();
        List<Map<String, Object>> rows =
                harness.rows(
                        "MATCH (:Entity {id: $project})-[:HAS_SECRET]->(s:Secret {id: $id})"
                                + " OPTIONAL MATCH (s)-[r:FROM]->(f:SourceFile)"
                                + " RETURN s.env AS env, s.secret_value AS value, s.source AS"
                                + " source, f.id AS file, r.line AS line",
                        Map.of("project", PROJECT, "id", id));

        assertThat(rows).as("Secret %s", id).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("env")).isEqualTo(secret.get("env").asString());
        assertThat(row.get("value")).isEqualTo(VALUE_IN.get(ExpectedScan.fileOf(from)));
        assertThat(row.get("source")).isEqualTo(SCAN);
        assertSourced(row, from);
    }

    @ParameterizedTest(name = "{0} -> {1} @{2}")
    @MethodSource("io.github.ismetdyn.agentkb.server.scan.ExpectedScan#relations")
    void an_expected_relation_of_the_project_is_open(String type, String target, String env) {
        assertThat(
                        harness.rows(
                                "MATCH (:Entity {id: $project})-[r]->(:Entity {id: $target})"
                                        + " WHERE type(r) = $type AND r.env = $env"
                                        + " AND r.valid_to IS NULL RETURN r.source AS source",
                                Map.of(
                                        "project", PROJECT, "target", target, "type", type, "env",
                                        env)))
                .extracting(row -> row.get("source"))
                .containsExactly(SCAN);
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("io.github.ismetdyn.agentkb.server.scan.ExpectedScan#entities")
    void a_new_entity_is_observed_with_the_label_of_its_type(String id, String type) {
        String label =
                YAMLMapper.builder()
                        .build()
                        .readTree(TYPES.toFile())
                        .at("/entity_types/" + type + "/label")
                        .asString();

        assertThat(
                        harness.rows(
                                "MATCH (e:Entity {id: $id}) RETURN labels(e) AS labels,"
                                        + " e.status AS status",
                                Map.of("id", id)))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("labels"))
                                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                                    .contains(label);
                            assertThat(row.get("status")).isEqualTo("observed");
                        });
    }

    private static void assertSourced(Map<String, Object> row, String from) {
        assertThat(row.get("file"))
                .as("FROM of %s", row)
                .isEqualTo(REPO + ":" + ExpectedScan.fileOf(from));
        assertThat(row.get("line"))
                .as("FROM line of %s", row)
                .isInstanceOf(Number.class)
                .satisfies(
                        line ->
                                assertThat(((Number) line).intValue())
                                        .isEqualTo(ExpectedScan.lineOf(from)));
    }
}
