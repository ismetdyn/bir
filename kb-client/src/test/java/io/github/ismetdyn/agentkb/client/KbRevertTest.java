package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.ApiStub.jsonField;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/CLIENTS.md §2, ADR-0022 decision 3: {@code kb revert --batch <id>} sends {@code POST
 * /api/v1/revert {"batch": id}} (answer {@code {"batch", "deleted", "reopened",
 * "deletedSecretValues"}}; a refusal is 400 {@code {"error", "hint"}}). Exit codes: 0 success, 1
 * error, 3 server unreachable (KbCheckTest).
 */
class KbRevertTest {

    private static final String REVERT = "/api/v1/revert";
    private static final String BATCH = "01KBTESTBATCH0000000000001";

    @TempDir Path workDir;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void revert_posts_the_batch_and_reports_what_it_did() {
        server.answer(
                "POST",
                REVERT,
                200,
                "{\"batch\":\""
                        + BATCH
                        + "\",\"deleted\":17,\"reopened\":4,\"deletedSecretValues\":2}");

        Result result = server.kb(workDir, "revert", "--batch", BATCH);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains(BATCH)
                .contains("17")
                .contains("4")
                .contains("secret values deleted for good")
                .contains(": 2");
        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("POST");
                            assertThat(request.rawPath()).isEqualTo(REVERT);
                            assertThat(request.headers()).containsKey("Content-type");
                            assertThat(request.headers().get("Content-type"))
                                    .anyMatch(type -> type.startsWith("application/json"));
                            assertThat(request.body()).containsPattern(jsonField("batch", BATCH));
                            assertThat(request.headers().keySet())
                                    .noneMatch("Authorization"::equalsIgnoreCase)
                                    .noneMatch("Origin"::equalsIgnoreCase);
                        });
    }

    /** The batch id is JSON-quoted, so an id with a quote cannot add a field to the body. */
    @Test
    void revert_quotes_the_batch_in_the_body() {
        server.answer("POST", REVERT, 200, "{\"batch\":\"x\",\"deleted\":0,\"reopened\":0}");

        server.kb(workDir, "revert", "--batch", "a\"b");

        assertThat(server.requests())
                .singleElement()
                .satisfies(request -> assertThat(request.body()).contains("\"a\\\"b\""));
    }

    @Test
    void revert_without_a_batch_is_a_usage_error_and_sends_nothing() {
        Result result = server.kb(workDir, "revert");

        assertThat(result.exitCode()).isNotZero();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void a_refused_revert_shows_error_and_hint_and_exits_one() {
        server.answer(
                "POST",
                REVERT,
                400,
                "{\"error\":\"a later batch built on this one\","
                        + "\"hint\":\"Revert batch 01KBTESTLATER first.\"}");

        Result result = server.kb(workDir, "revert", "--batch", BATCH);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("a later batch built on this one")
                .contains("Revert batch 01KBTESTLATER first.")
                .doesNotContain("Exception");
    }
}
