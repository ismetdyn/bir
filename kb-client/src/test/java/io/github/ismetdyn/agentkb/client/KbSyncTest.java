package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/CLIENTS.md §2, SPEC §10: {@code kb sync} sends {@code POST /api/v1/sync} and prints the
 * answer {@code {"commit", "pushed", "nodes", "edges", "conflicts"}}: the commit it made (null when
 * nothing changed), whether it reached the remote (false: no remote, commit only), the rows written
 * and the conflict signals the merge opened. A refusal (row count mismatch, push still rejected
 * after the retries, remote unreachable) is 400 {@code {"error", "hint"}} and exit 1; an
 * unreachable server exits 3 (KbCheckTest).
 */
class KbSyncTest {

    private static final String SYNC = "/api/v1/sync";
    private static final String COMMIT = "4f1c2a9e7b3d5c8a1f0e6d4b2a9c7e5f3d1b8a60";

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
    void sync_posts_once_and_reports_the_commit_rows_and_conflicts() {
        server.answer(
                "POST",
                SYNC,
                200,
                "{\"commit\":\""
                        + COMMIT
                        + "\",\"pushed\":true,\"nodes\":4501,\"edges\":5702,\"conflicts\":31}");

        Result result = server.kb(workDir, "sync");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains(COMMIT, "4501", "5702", "31");
        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("POST");
                            assertThat(request.rawPath()).isEqualTo(SYNC);
                        });
    }

    /** Without a remote the commit stays local; the output must not read like a pushed one. */
    @Test
    void a_local_only_sync_reads_differently_from_a_pushed_one() {
        String rest = "\",\"nodes\":1,\"edges\":0,\"conflicts\":0,\"pushed\":";
        server.answer("POST", SYNC, 200, "{\"commit\":\"" + COMMIT + rest + "true}");
        Result pushed = server.kb(workDir, "sync");
        server.answer("POST", SYNC, 200, "{\"commit\":\"" + COMMIT + rest + "false}");

        Result local = server.kb(workDir, "sync");

        assertThat(pushed.exitCode()).as(pushed.stderr()).isZero();
        assertThat(local.exitCode()).as(local.stderr()).isZero();
        assertThat(local.stdout()).contains(COMMIT).isNotEqualTo(pushed.stdout());
    }

    @Test
    void a_refused_sync_exits_one_with_the_error_and_the_hint() {
        server.answer(
                "POST",
                SYNC,
                400,
                "{\"error\":\"the snapshot has 1 node rows, the graph 45\","
                        + "\"hint\":\"Nothing was committed; run kb check.\"}");

        Result result = server.kb(workDir, "sync");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("1 node rows", "kb check");
    }
}
