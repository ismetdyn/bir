package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariant 2 at the scanner's edges: a credential in a URL query or a webhook path never becomes a
 * fact, a repository cannot take over an entity of another type or another group's project, and two
 * different values under one secret id raise a conflict instead of silently replacing each other.
 */
class ScanSecretBoundaryTest {

    private static final String PROD_YML = "src/main/resources/application-prod.yml";
    private static final String SECRET_VALUE =
            "MATCH (s:Secret {id: $id}) RETURN s.secret_value AS value";
    private static final String CONFLICTS =
            "MATCH (s:Signal {kind: 'conflict', key: $id}) RETURN s.payload AS payload";

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
    void a_url_fact_keeps_no_user_query_or_fragment() {
        String sig = "kbtest-fake-sig-6b1e09d4c27a835f";
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-sas",
                        Map.of(
                                PROD_YML,
                                """
                                azure:
                                  storage:
                                    url: https://zz-blob.ornek.example:8443/c/d?sv=2022&sig=%s#x
                                """
                                        .formatted(sig)));

        harness.scanner.scan(project);

        assertThat(harness.rows("MATCH (f:Fact {key: 'url'}) RETURN f.value AS value", Map.of()))
                .extracting(row -> row.get("value"))
                .containsExactly("https://zz-blob.ornek.example:8443/c/d");
        assertThat(harness.state().toString()).doesNotContain(sig).doesNotContain("sv=2022");
    }

    /**
     * Lead decision CP5 (2) narrowed decision (B): a repository of another group (other/) with the
     * project's name is refused instead of joining. Its secrets reach neither the graph nor the
     * refusal, and the project keeps only its own Repo.
     */
    @Test
    void a_repository_of_another_group_with_the_same_name_is_refused_leaking_no_secret() {
        String name = "zz-billing";
        List<String> passwords =
                List.of(
                        "kbtest-fake-1f6c0a9e3b72d854",
                        "kbtest-fake-8e2d5b07c1a96f34",
                        "kbtest-fake-4a0b7e3d92c1f586",
                        "kbtest-fake-d3c95f1a7e0b2846");
        Path own =
                ScanProjects.springProject(harness.root, name, passwords.get(0), passwords.get(1));
        harness.scanner.scan(own);
        Path clone =
                ScanProjects.springProject(
                        harness.root.resolve("clones"), name, passwords.get(2), passwords.get(3));
        ScanProjects.git(
                clone,
                "remote",
                "set-url",
                "origin",
                "https://github.example/other/zz-billing.git");

        Throwable refused = catchThrowable(() -> harness.scanner.scan(clone));

        assertThat(refused)
                .isInstanceOfSatisfying(
                        WriteRejectedException.class,
                        rejected -> assertThat(rejected.hint()).as("{error, hint}").isNotBlank());
        assertThat(
                        harness.rows(
                                "MATCH (:Entity {id: $id})-[:HAS_REPO]->(r:Repo) RETURN r.id AS id",
                                Map.of("id", name)))
                .extracting(row -> row.get("id"))
                .containsExactly("zz-grup/" + name);
        // Lead decision CP5 (c): the own project's passwords rightly sit in secret_value; no
        // password may appear anywhere else, and the refused clone's not even there.
        String outside =
                harness.state("secret_value")
                        + " "
                        + refused.getMessage()
                        + " "
                        + ((WriteRejectedException) refused).hint();
        assertThat(passwords).noneMatch(outside::contains);
        assertThat(harness.state().toString())
                .as("the refused clone's secrets are not stored")
                .doesNotContain(passwords.get(2))
                .doesNotContain(passwords.get(3));
    }

    @Test
    void a_repository_named_like_an_entity_of_another_type_is_refused() {
        String name = "zz-orders-db";
        harness.writes.write(
                new WriteRequest(
                        Source.USER,
                        name,
                        "database",
                        List.of(new FactInput("engine", "oracle", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.parse("2026-01-15T09:00:00Z")));
        Path project =
                ScanProjects.springProject(
                        harness.root,
                        name,
                        "kbtest-fake-27e0c4b9a15d8f63",
                        "kbtest-fake-b80f3e6d2a19c574");

        assertThatThrownBy(() -> harness.scanner.scan(project))
                .isInstanceOfSatisfying(
                        WriteRejectedException.class,
                        refused -> assertThat(refused.hint()).as("{error, hint}").isNotBlank());
        assertThat(harness.rows("MATCH (s:Secret) RETURN s.id AS id")).isEmpty();
        assertThat(harness.rows("MATCH (r:Repo) RETURN r.id AS id"))
                .as("nothing of the refused scan was written")
                .isEmpty();
    }

    /**
     * Lead decision (C): a webhook URL carries its token in the path, which url facts keep. Slack
     * and Discord shapes checked on 2026-11-05 in vendor docs (Context7 /websites/slack_dev,
     * /discord/discord-api-docs). The url fact is not written; the value is reported nowhere, and
     * either a Secret holds it or the report names its line as not written.
     */
    @Test
    void a_webhook_url_with_a_token_in_its_path_is_no_url_fact() {
        String slackToken = "kbtestFakeSlackHook00001";
        String discordToken = ("kbtest_FakeDiscordWebhookToken-" + "x".repeat(68)).substring(0, 68);
        String report = "https://zz-hooks-report.ornek.example/v1";
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-hooks",
                        Map.of(
                                PROD_YML,
                                """
                                app:
                                  notify:
                                    slack:
                                      url: https://hooks.slack.com/services/T0KBTEST01/B0KBTEST01/%s
                                    discord:
                                      url: https://discord.com/api/webhooks/347114750880120863/%s
                                  report:
                                    url: %s
                                """
                                        .formatted(slackToken, discordToken, report)));

        ScanResult result = harness.scanner.scan(project);

        assertThat(harness.rows("MATCH (f:Fact {key: 'url'}) RETURN f.value AS value"))
                .extracting(row -> row.get("value"))
                .as("the ordinary url is still written, the webhooks are not")
                .containsExactly(report);
        String outside = harness.state("secret_value") + " " + result;
        assertThat(outside).doesNotContain(slackToken).doesNotContain(discordToken);
        assertThat(reported(result, 4, "slack.url")).as("slack line reported").isTrue();
        assertThat(reported(result, 6, "discord.url")).as("discord line reported").isTrue();
    }

    /** As a Secret named {@code name}, or as a skipped {@code <path>:<line>:} of the report. */
    private static boolean reported(ScanResult result, int line, String name) {
        String at = PROD_YML + ":" + line + ":";
        return result.skipped().stream().anyMatch(skip -> skip.startsWith(at))
                || result.secrets().stream().anyMatch(id -> id.endsWith(name));
    }

    @Test
    void two_values_under_one_secret_id_in_one_scan_raise_a_conflict_and_keep_the_first() {
        String first = "kbtest-fake-a3e70b5d19c2f468";
        String second = "kbtest-fake-5c18f0e2d7b94a36";
        String yml =
                """
                spring:
                  datasource:
                    password: %s
                """;
        Path project =
                ScanProjects.project(
                        harness.root,
                        "zz-twins",
                        Map.of(
                                PROD_YML,
                                yml.formatted(first),
                                "src/main/resources/bootstrap-prod.yml",
                                yml.formatted(second)));

        harness.scanner.scan(project);
        harness.scanner.scan(project);

        String id = "zz-twins/prod/spring.datasource.password";
        assertThat(secretValue(id)).as("the file git lists first").isEqualTo(first);
        assertThat(harness.rows(CONFLICTS, Map.of("id", id)))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.get("payload").toString())
                                        .contains(id)
                                        .doesNotContain(first)
                                        .doesNotContain(second));
    }

    private Object secretValue(String id) {
        List<Map<String, Object>> rows = harness.rows(SECRET_VALUE, Map.of("id", id));
        assertThat(rows).as("secret %s", id).hasSize(1);
        return rows.getFirst().get("value");
    }
}
