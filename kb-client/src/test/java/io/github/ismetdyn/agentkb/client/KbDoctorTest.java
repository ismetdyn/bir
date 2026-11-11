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

/**
 * plan.md H7, docs/CLIENTS.md §2: {@code kb doctor} sends {@code GET /api/v1/doctor} and prints
 * each check as ok or fail, with the hint of a failed one. Exit 0 if every check passed, 1 if one
 * failed or the server refused, 3 if the server cannot be reached.
 */
class KbDoctorTest {

    private static final String DOCTOR = "/api/v1/doctor";
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
    void a_failed_check_is_printed_with_its_hint_and_exits_one() {
        server.answer("GET", DOCTOR, 200, KbCommandCases.contract("doctor-answer.json"));

        Result result = server.kb(workDir, "doctor");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(result.stdout())
                .contains(
                        "git  ok\n",
                        "git-lfs  fail  Install git-lfs; documents over the LFS threshold need"
                                + " it.\n",
                        "repo  ok\n",
                        "lfs-filter  ok\n",
                        "data-dir  fail  Make the data directory");
        assertThat(server.requests())
                .singleElement()
                .extracting(ApiStub.Request::method)
                .isEqualTo("GET");
    }

    @Test
    void every_check_passing_exits_zero() {
        server.answer(
                "GET",
                DOCTOR,
                200,
                "{\"ok\":true,\"checks\":[{\"name\":\"git\",\"ok\":true,\"hint\":null}]}");

        Result result = server.kb(workDir, "doctor");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("git  ok");
    }

    /** A server that says ok while a check failed is not believed. */
    @Test
    void a_failed_check_exits_one_even_if_the_answer_says_ok() {
        server.answer(
                "GET",
                DOCTOR,
                200,
                "{\"ok\":true,\"checks\":[{\"name\":\"git\",\"ok\":false,\"hint\":\"install\"}]}");

        Result result = server.kb(workDir, "doctor");

        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void a_refusal_exits_one_with_the_servers_error() {
        server.answer(
                "GET", DOCTOR, 500, "{\"error\":\"kbtest-refused\",\"hint\":\"kbtest-hint\"}");

        Result result = server.kb(workDir, "doctor");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("500", "kbtest-refused; kbtest-hint");
    }

    @Test
    void an_answer_without_its_fields_exits_one() {
        server.answer("GET", DOCTOR, 200, "{}");

        Result result = server.kb(workDir, "doctor");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void an_unreachable_server_exits_three() throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }

        Result result = KbProcess.run(closed, "", workDir, "doctor");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(UNREACHABLE);
    }
}
