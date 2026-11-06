package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/CLIENTS.md §2: {@code kb check} sends {@code GET /api/v1/check}, answered {@code {"clean",
 * "problems": [{"kind", "id", "detail"}]}}, and lists the problems. Exit codes for the three
 * maintenance commands: 0 success, 1 error or finding (check with problems), 3 server unreachable.
 */
class KbCheckTest {

    private static final String CHECK = "/api/v1/check";
    private static final int UNREACHABLE = 3;

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
    void check_of_a_clean_repo_exits_zero() {
        server.answer("GET", CHECK, 200, "{\"clean\":true,\"problems\":[]}");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.requests())
                .singleElement()
                .extracting(ApiStub.Request::method)
                .isEqualTo("GET");
    }

    @Test
    void check_with_problems_lists_them_and_exits_one() {
        server.answer(
                "GET",
                CHECK,
                200,
                "{\"clean\":false,\"problems\":["
                        + "{\"kind\":\"orphan-edge\",\"id\":\"01KBTESTEDGE\","
                        + "\"detail\":\"points to missing node kbtest-ghost\"},"
                        + "{\"kind\":\"git-fsck\",\"id\":\"repo\","
                        + "\"detail\":\"error: object file is empty\"}]}");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("orphan-edge", "01KBTESTEDGE", "kbtest-ghost", "git-fsck", "empty");
    }

    @Test
    void a_server_failure_exits_one() {
        server.answer("GET", CHECK, 500, "{\"error\":\"internal error\",\"trace_id\":\"t-1\"}");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"check", "restore", "revert --batch 01KBTESTBATCH", "sync"})
    void an_unreachable_server_exits_three(String command) throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }

        Result result = KbProcess.run(closed, "", workDir, command.split(" "));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(UNREACHABLE);
        assertThat(result.stderr()).contains("127.0.0.1");
    }
}
