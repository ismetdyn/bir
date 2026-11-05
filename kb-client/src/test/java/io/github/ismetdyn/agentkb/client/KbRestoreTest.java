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
 * docs/CLIENTS.md §2, ADR-0022 decisions 3 and 38: {@code kb restore [--at <commit>]} sends {@code
 * POST /api/v1/restore {"at"?: commit}} and prints the counts of the answer {@code {"nodes",
 * "edges", "keptSecrets", "keptSignals", "droppedEdges", "droppedSecretValues"}}, so the user sees
 * what local data stayed. A refusal is 400 {@code {"error", "hint"}} and exit 1.
 */
class KbRestoreTest {

    private static final String RESTORE = "/api/v1/restore";

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
    void restore_without_at_sends_no_commit_and_reports_the_counts() {
        server.answer(
                "POST",
                RESTORE,
                200,
                "{\"nodes\":4501,\"edges\":5702,\"keptSecrets\":31,\"keptSignals\":42,"
                        + "\"droppedEdges\":53,\"droppedSecretValues\":67}");

        Result result = server.kb(workDir, "restore");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("4501", "5702", "31", "42", "53", "67");
        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("POST");
                            assertThat(request.rawPath()).isEqualTo(RESTORE);
                            assertThat(request.body()).doesNotContainPattern("\"at\"\\s*:\\s*\"");
                        });
    }

    @Test
    void restore_at_sends_the_commit() {
        server.answer(
                "POST",
                RESTORE,
                200,
                "{\"nodes\":1,\"edges\":0,\"keptSecrets\":0,\"keptSignals\":0,\"droppedEdges\":0}");

        Result result = server.kb(workDir, "restore", "--at", "HEAD~3");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.body())
                                        .containsPattern(jsonField("at", "HEAD~3")));
    }

    @Test
    void a_refused_restore_exits_one_with_the_hint() {
        server.answer(
                "POST",
                RESTORE,
                400,
                "{\"error\":\"no such commit\",\"hint\":\"List commits with git log in ~/kb.\"}");

        Result result = server.kb(workDir, "restore", "--at", "nope");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no such commit").contains("git log");
    }
}
