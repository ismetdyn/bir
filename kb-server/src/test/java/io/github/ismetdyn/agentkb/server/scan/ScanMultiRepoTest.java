package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decisions (B) and CP5 (2): a project may span repositories. When the project id of a scan
 * names an existing Project, the scan adds a HAS_REPO to its Repo only if the project has no Repo
 * yet or every Repo it has is in the new repo's group (the repo id up to its last '/'); else the
 * scan is refused with {error, hint}. A scan of one repository leaves what the other one reported
 * open. A non-project entity still refuses (ScanSecretBoundaryTest).
 */
class ScanMultiRepoTest {

    private static final String PROD_YML = "src/main/resources/application-prod.yml";
    private static final String HAS_REPO =
            "MATCH (:Entity {id: $id})-[:HAS_REPO]->(r:Repo) RETURN r.id AS id";
    private static final String OPEN_FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) WHERE f.valid_to IS NULL"
                    + " RETURN f.key + '=' + f.value + '@' + f.env AS fact";
    private static final String DEPENDS_ON =
            "MATCH (:Entity {id: $id})-[r:DEPENDS_ON]->(t:Entity) WHERE r.valid_to IS NULL"
                    + " RETURN t.id AS id";
    private static final String PROJECT_IDS =
            "MATCH (e:Entity) WHERE e.id STARTS WITH $prefix RETURN e.id AS id";

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

    /** Lead decision (2): the second repository is in the project's group (zz-grup). */
    @Test
    void a_project_in_two_repositories_of_its_group_has_a_repo_each_and_keeps_what_both_reported() {
        String name = "zz-cp5-span";
        Path app = ScanProjects.project(harness.root, name, Map.of(PROD_YML, datasource(name)));
        // A deploy repository of the same group, zz-grup/zz-ops, with a folder for the project.
        Path deploy =
                ScanProjects.project(
                                harness.root, "zz-ops", Map.of(name + "/k8s/app.yaml", k8s(name)))
                        .resolve(name);

        harness.scanner.scan(app);
        harness.scanner.scan(deploy);
        ScanResult again = harness.scanner.scan(app);

        assertThat(ids(HAS_REPO, Map.of("id", name)))
                .containsExactlyInAnyOrder("zz-grup/" + name, "zz-grup/zz-ops");
        assertThat(ids(PROJECT_IDS, Map.of("prefix", name)))
                .as("one project entity, plus the database of the first repository")
                .containsExactlyInAnyOrder(name, name + "-db");
        assertThat(harness.rows(OPEN_FACTS, Map.of("id", name)))
                .extracting(row -> row.get("fact"))
                .as("the scan of one repository does not close what the other reported")
                .contains("namespace=zz-cp5-span-prod@prod");
        assertThat(ids(DEPENDS_ON, Map.of("id", name))).containsExactly(name + "-db");
        assertThat(List.of(again.added(), again.closed())).containsOnly(0);
    }

    /**
     * Lead decision (2): a repository of another group with the project's name is another team's
     * project, not this one; the scan is refused with {error, hint} and writes nothing, so the user
     * links it on purpose instead.
     */
    @Test
    void a_repository_of_another_group_with_the_projects_name_is_refused_and_writes_nothing() {
        String name = "zz-cp5-other";
        Path app = ScanProjects.project(harness.root, name, Map.of(PROD_YML, datasource(name)));
        harness.scanner.scan(app);
        Map<String, Object> before = harness.state();
        Path stranger =
                ScanProjects.project(
                        harness.root.resolve("elsewhere"), name, Map.of("k8s/app.yaml", k8s(name)));
        ScanProjects.git(
                stranger,
                "remote",
                "set-url",
                "origin",
                "https://git.ornek.example/zz-baska/" + name + ".git");

        assertThatThrownBy(() -> harness.scanner.scan(stranger))
                .isInstanceOfSatisfying(
                        WriteRejectedException.class,
                        refused -> assertThat(refused.hint()).as("{error, hint}").isNotBlank());
        assertThat(harness.state()).as("nothing of the refused scan was written").isEqualTo(before);
    }

    /** Lead decision (2): one Repo of another group is enough to refuse, not only the first. */
    @Test
    void a_project_whose_repos_are_in_its_group_refuses_a_third_repository_of_another_group() {
        String name = "zz-cp5-third";
        harness.scanner.scan(
                ScanProjects.project(harness.root, name, Map.of(PROD_YML, datasource(name))));
        harness.scanner.scan(
                ScanProjects.project(
                                harness.root, "zz-ops", Map.of(name + "/k8s/app.yaml", k8s(name)))
                        .resolve(name));
        Path stranger =
                ScanProjects.project(
                        harness.root.resolve("elsewhere"), name, Map.of("k8s/app.yaml", k8s(name)));
        ScanProjects.git(
                stranger,
                "remote",
                "set-url",
                "origin",
                "https://git.ornek.example/zz-baska/" + name + ".git");

        assertThatThrownBy(() -> harness.scanner.scan(stranger))
                .isInstanceOf(WriteRejectedException.class);
        assertThat(ids(HAS_REPO, Map.of("id", name)))
                .containsExactlyInAnyOrder("zz-grup/" + name, "zz-grup/zz-ops");
    }

    /** Lead decision (2): a project the user wrote before any scan has no Repo yet, so it joins. */
    @Test
    void a_project_without_a_repo_joins_the_first_repository_scanned_under_its_name() {
        String name = "zz-cp5-noted";
        harness.writes.write(
                new WriteRequest(
                        Source.USER,
                        name,
                        "project",
                        List.of(new FactInput("owner", "zz-takim", null, null)),
                        List.of(),
                        null,
                        null,
                        Instant.parse("2026-01-15T09:00:00Z")));

        harness.scanner.scan(
                ScanProjects.project(harness.root, name, Map.of(PROD_YML, datasource(name))));

        assertThat(ids(HAS_REPO, Map.of("id", name))).containsExactly("zz-grup/" + name);
        assertThat(ids(DEPENDS_ON, Map.of("id", name))).containsExactly(name + "-db");
    }

    @Test
    void a_repository_scanned_without_a_remote_keeps_its_project_once_it_has_one()
            throws IOException {
        String name = "zz-cp5-local";
        Path project = harness.root.resolve(name);
        Files.createDirectories(project.resolve(PROD_YML).getParent());
        Files.writeString(project.resolve(PROD_YML), datasource(name));
        ScanProjects.git(project, "init", "--quiet");
        ScanProjects.commitAll(project, "initial");
        harness.scanner.scan(project);
        assertThat(ids(HAS_REPO, Map.of("id", name)))
                .as("precondition: a local repository")
                .containsExactly("local/" + name);

        ScanProjects.git(
                project,
                "remote",
                "add",
                "origin",
                "https://git.ornek.example/zz-grup/" + name + ".git");
        harness.scanner.scan(project);

        assertThat(ids(HAS_REPO, Map.of("id", name)))
                .containsExactlyInAnyOrder("local/" + name, "zz-grup/" + name);
        assertThat(ids(PROJECT_IDS, Map.of("prefix", name)))
                .containsExactlyInAnyOrder(name, name + "-db");
        assertThat(ids(DEPENDS_ON, Map.of("id", name))).containsExactly(name + "-db");
    }

    private static String k8s(String name) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                  namespace: %s-prod
                """
                .formatted(name, name);
    }

    private static String datasource(String name) {
        return """
                spring:
                  datasource:
                    url: jdbc:postgresql://%s-db.ornek.example:5432/spandb
                """
                .formatted(name);
    }

    private List<Object> ids(String cypher, Map<String, Object> params) {
        return harness.rows(cypher, params).stream().map(row -> row.get("id")).toList();
    }
}
