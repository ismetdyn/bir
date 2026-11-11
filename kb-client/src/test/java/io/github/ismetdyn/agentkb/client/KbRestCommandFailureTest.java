package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbCommandCases.Case;
import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/CLIENTS.md §2 exit codes for every H6 command: 1 when the server refuses (its error and hint
 * on stderr), when its answer lacks a field (plan.md H2) or when the command line is wrong; 3 when
 * the server cannot be reached. Nothing is half-printed on stdout.
 */
class KbRestCommandFailureTest {

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

    static Stream<Case> cases() {
        return KbCommandCases.all();
    }

    static Stream<Case> jsonCases() {
        return KbCommandCases.all().filter(Case::answersJson);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void a_refusal_prints_the_servers_error_and_hint_and_exits_one(Case command) {
        server.answer(
                command.method(),
                command.path(),
                400,
                "{\"error\":\"kbtest-refused\",\"hint\":\"kbtest-hint\"}");

        Result result = server.kb(workDir, command.args().toArray(String[]::new));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("400", "kbtest-refused; kbtest-hint");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonCases")
    void an_answer_without_its_fields_exits_one_and_prints_nothing(Case command) {
        server.answer(command.method(), command.path(), 200, "{}");

        Result result = server.kb(workDir, command.args().toArray(String[]::new));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(result.stdout()).as("nothing half-printed").isEmpty();
        assertThat(result.stderr().strip())
                .startsWith("kb: ")
                .doesNotContain("unexpected failure", "Exception", "\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void an_unreachable_server_exits_three(Case command) throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }

        Result result = KbProcess.run(closed, "", workDir, command.args().toArray(String[]::new));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(UNREACHABLE);
        assertThat(result.stderr()).contains("127.0.0.1");
    }

    /**
     * Bad input is exit 1 (docs/CLIENTS.md §2 knows no 2), and nothing is sent. An id of {@code .}
     * or {@code ..} would be a dot segment the server resolves to another route even encoded
     * (Tomcat decodes %2E), so kb refuses it (H6 review); --alias and --clear-aliases exclude each
     * other.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "fact set ornek-app-01 ip",
                "resolve 01KBTESTCONFLICT",
                "rel add ornek-app-01 BEHIND",
                "search",
                "stale --days many",
                "archive ..",
                "fact close .",
                "show ..",
                "rel close ..",
                "edit ornek-app-01 --alias app01 --clear-aliases"
            })
    void a_wrong_command_line_exits_one_without_a_request(String command) {
        Result result = server.kb(workDir, command.split(" "));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(server.requests()).isEmpty();
    }
}
