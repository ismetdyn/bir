package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.SESSION_START;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.gitWorkingCopy;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.sessionStart;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * integrations/kb-hook.sh is only a carrier (ARCHITECTURE §11): it hands the client's stdin to
 * {@code /hooks/<event>?client=} and prints the answer. It never blocks a session: server down,
 * hooks off or an unknown event give empty output and exit 0 (fail-open). Runs the real script with
 * bash and curl.
 */
class KbHookScriptTest extends RunningServerTestBase {

    private static final Path SCRIPT = Path.of("..", "integrations", "kb-hook.sh");
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final int STUB_WAIT_MILLIS = 200;
    private static final List<String> PROXY_VARIABLES =
            List.of(
                    "http_proxy",
                    "HTTP_PROXY",
                    "https_proxy",
                    "HTTPS_PROXY",
                    "all_proxy",
                    "ALL_PROXY",
                    "no_proxy",
                    "NO_PROXY");

    @TempDir Path workingCopy;

    private record Run(int exit, String stdout) {}

    @Test
    void the_script_carries_the_brief_from_the_running_server() throws Exception {
        Path repo = gitWorkingCopy(workingCopy, "git@git.ornek.example:ornek-grup/ornek-proje.git");

        Run run =
                run(
                        Map.of("AGENTKB_URL", "http://127.0.0.1:" + port),
                        sessionStart("codex", repo).toString(),
                        SESSION_START,
                        "codex");

        assertThat(run.exit()).isZero();
        assertThat(
                        JsonMapper.builder()
                                .build()
                                .readTree(run.stdout())
                                .at("/hookSpecificOutput/additionalContext")
                                .asString(""))
                .contains("ornek-proje");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"session-start", "user-prompt-submit", "post-tool-use"})
    void a_server_that_is_down_gives_empty_output_and_exit_0(String event) throws Exception {
        Run run =
                run(
                        Map.of("AGENTKB_URL", "http://127.0.0.1:" + closedPort()),
                        sessionStart("claude", workingCopy).toString(),
                        event,
                        "claude");

        assertThat(run).isEqualTo(new Run(0, ""));
    }

    @Test
    void hooks_switched_off_or_an_unknown_event_give_empty_output_and_exit_0() throws Exception {
        String payload = sessionStart("claude", workingCopy).toString();
        String server = "http://127.0.0.1:" + port;

        assertThat(
                        run(
                                Map.of("AGENTKB_URL", server, "KB_HOOKS", "off"),
                                payload,
                                SESSION_START,
                                "claude"))
                .isEqualTo(new Run(0, ""));
        assertThat(run(Map.of("AGENTKB_URL", server), payload, "stop", "claude"))
                .isEqualTo(new Run(0, ""));
    }

    /**
     * Invariants 3 and 4, ADR-0020: the hook carries prompts and answers, so it talks to this
     * machine only. Any other host, also one hidden behind user info, gets nothing and its reply
     * reaches no session. 0.0.0.0 is no loopback address but still reaches the stub on Linux.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "http://0.0.0.0:%d",
                "http://127.0.0.1:%d@0.0.0.0:%d",
                "http://127.0.0.1.%d.example:%d",
                "http://localhost.example:%d"
            })
    void a_url_that_is_not_this_machine_gets_no_request(String url) throws Exception {
        try (ServerSocket stub = new ServerSocket(0)) {
            String target = url.replace("%d", String.valueOf(stub.getLocalPort()));
            Run run =
                    run(
                            Map.of(
                                    "AGENTKB_URL",
                                    target,
                                    "http_proxy",
                                    "http://127.0.0.1:" + stub.getLocalPort()),
                            sessionStart("claude", workingCopy).toString(),
                            SESSION_START,
                            "claude");

            assertThat(run).isEqualTo(new Run(0, ""));
            assertThat(connected(stub)).as(target).isFalse();
        }
    }

    /** A proxy from the environment would carry the prompt off the machine too. */
    @Test
    void a_proxy_from_the_environment_is_not_used() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            String url = "http://127.0.0.1:" + proxy.getLocalPort();
            Run run =
                    run(
                            Map.of(
                                    "AGENTKB_URL",
                                    "http://127.0.0.1:" + closedPort(),
                                    "http_proxy",
                                    url,
                                    "all_proxy",
                                    url),
                            sessionStart("claude", workingCopy).toString(),
                            SESSION_START,
                            "claude");

            assertThat(run).isEqualTo(new Run(0, ""));
            assertThat(connected(proxy)).isFalse();
        }
    }

    /** A connection the script made waits in the backlog even after the script has ended. */
    private static boolean connected(ServerSocket stub) throws IOException {
        stub.setSoTimeout(STUB_WAIT_MILLIS);
        try (Socket ignored = stub.accept()) {
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Run run(Map<String, String> env, String stdin, String... args)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString());
        builder.command().addAll(List.of(args));
        builder.environment().remove("AGENTKB_URL");
        builder.environment().remove("KB_HOOKS");
        PROXY_VARIABLES.forEach(builder.environment()::remove);
        builder.environment().putAll(env);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process script = builder.start();
        try (var in = script.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String stdout = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(script.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new Run(script.exitValue(), stdout);
    }
}
