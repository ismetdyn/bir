package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.MarkdownView;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
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
 * ADR-0026: a well-known placeholder such as {@code postgres} is no secret value, as secret
 * scanners treat it. The scan keeps the Secret and its name without a value and reports it, so
 * reads that hold the word (an image, an entity) are not masked.
 */
class ScanPlaceholderSecretTest {

    private static final String NAME = "zz-placeholder-pg";
    private static final String SECRETS =
            "MATCH (s:Secret) RETURN s.name AS name, s.secret_value IS NOT NULL AS stored";

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
    void a_placeholder_password_is_kept_unset_and_reported() {
        Path project =
                ScanProjects.project(
                        harness.root,
                        NAME,
                        Map.of(
                                "docker-compose.yml",
                                """
                                services:
                                  db:
                                    image: postgres:16
                                    environment:
                                      POSTGRES_PASSWORD: postgres
                                """));

        ScanResult result = harness.scanner.scan(project);

        assertThat(harness.rows(SECRETS))
                .containsExactly(Map.of("name", "POSTGRES_PASSWORD", "stored", false));
        assertThat(result.skipped())
                .anySatisfy(
                        skipped ->
                                assertThat(skipped)
                                        .startsWith("docker-compose.yml:")
                                        .endsWith(": placeholder, not stored"));
        harness.writes.write(
                new WriteRequest(
                        Source.USER,
                        "zz-placeholder-db",
                        "server",
                        List.of(new FactInput("note", "postgres-db primary", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));
        assertThat(
                        MarkdownView.render(
                                new KnowledgeQueries(harness.store)
                                        .entity("zz-placeholder-db")
                                        .orElseThrow()))
                .as("not stored, so not masked")
                .contains("postgres-db primary");
    }

    /** A value under four characters is no secret (ADR-0026): kept unset, reported. */
    @Test
    void a_value_under_four_characters_is_kept_unset_and_reported() {
        Path project = ScanProjects.project(harness.root, NAME, Map.of(".env", "PASSWORD=not\n"));

        ScanResult result = harness.scanner.scan(project);

        assertThat(harness.rows(SECRETS))
                .containsExactly(Map.of("name", "PASSWORD", "stored", false));
        assertThat(result.skipped()).contains(".env:1: placeholder, not stored");
    }

    /** F1: a scanned letters-only value has too little entropy to be a secret. */
    @Test
    void a_scanned_letters_only_value_is_kept_unset_and_not_masked() {
        Path project =
                ScanProjects.project(harness.root, NAME, Map.of(".env", "DB_PASSWORD=keycloak\n"));

        ScanResult result = harness.scanner.scan(project);

        assertThat(harness.rows(SECRETS))
                .containsExactly(Map.of("name", "DB_PASSWORD", "stored", false));
        assertThat(result.skipped()).contains(".env:1: placeholder, not stored");
        harness.writes.write(
                new WriteRequest(
                        Source.USER,
                        "zz-placeholder-kc",
                        "server",
                        List.of(new FactInput("note", "keycloak realm", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));
        assertThat(
                        MarkdownView.render(
                                new KnowledgeQueries(harness.store)
                                        .entity("zz-placeholder-kc")
                                        .orElseThrow()))
                .contains("keycloak realm");
    }
}
