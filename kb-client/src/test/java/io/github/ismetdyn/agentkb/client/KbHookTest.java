package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code kb hook <event> [client]} (ADR-0027 decision 4) carries a Claude Code or Codex hook to
 * {@code POST /hooks/<event>?client=} exactly as integrations/kb-hook.sh did with curl: stdin as
 * the JSON body, the server's answer on stdout. It never blocks a session: a server that is down,
 * slow or failing, or stdin that never ends, give no output and exit 0 (fail-open) within the 2 s
 * budget. What it never sends, and where, is KbHookSafetyTest's.
 */
class KbHookTest {

    private static final String ANSWER = HookStub.ANSWER;
    private static final String PAYLOAD = "{\"session_id\":\"s1\",\"cwd\":\"/tmp/x\"}";
    private static final Map<String, String> HOOKS_ON = Map.of("KB_HOOKS", "on");
    private static final Result SILENT = new Result(0, "", "");
    // The 2 s budget plus a JVM start on a loaded test machine; far below the stub's delay.
    private static final Duration WITHIN_BUDGET = Duration.ofSeconds(6);
    private static final Duration STUB_DELAY = Duration.ofSeconds(20);

    @TempDir Path workDir;

    private HookStub stub;

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    void the_hook_carries_stdin_to_the_event_endpoint_and_prints_the_answer() throws IOException {
        stub = new HookStub(200, Duration.ZERO);

        Result result =
                KbProcess.run(
                        stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook", "session-start", "codex");

        assertThat(result).isEqualTo(new Result(0, ANSWER, ""));
        assertThat(stub.requests)
                .containsExactly(
                        new HookStub.Request(
                                "POST",
                                "/hooks/session-start",
                                "client=codex",
                                "application/json",
                                PAYLOAD));
    }

    @Test
    void the_client_is_claude_unless_named() throws IOException {
        stub = new HookStub(200, Duration.ZERO);

        KbProcess.run(stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook", "user-prompt-submit");

        assertThat(stub.requests)
                .extracting(HookStub.Request::query)
                .containsExactly("client=claude");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"session-start", "user-prompt-submit", "post-tool-use"})
    void a_server_that_is_down_gives_no_output_and_exit_0(String event) throws IOException {
        Result result =
                KbProcess.run(
                        "http://127.0.0.1:" + closedPort(),
                        HOOKS_ON,
                        PAYLOAD,
                        workDir,
                        "hook",
                        event);

        assertThat(result).isEqualTo(SILENT);
    }

    /** curl -f dropped a non-2xx answer; so does kb hook. */
    @Test
    void a_failing_answer_gives_no_output_and_exit_0() throws IOException {
        stub = new HookStub(500, Duration.ZERO);

        Result result =
                KbProcess.run(stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook", "session-start");

        assertThat(result).isEqualTo(SILENT);
        assertThat(stub.requests).hasSize(1);
    }

    /**
     * SPEC §12: an answer after the 2 s budget never reaches the agent; kb does not wait for it.
     */
    @Test
    void a_slow_server_is_given_up_within_the_budget() throws IOException {
        stub = new HookStub(200, STUB_DELAY);
        long start = System.nanoTime();

        Result result =
                KbProcess.run(stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook", "session-start");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(WITHIN_BUDGET);
        assertThat(result).isEqualTo(SILENT);
    }

    /** A client that never closes stdin still gets kb's exit within the budget, and no request. */
    @Test
    void stdin_that_never_ends_is_given_up_within_the_budget() throws Exception {
        stub = new HookStub(200, Duration.ZERO);
        long start = System.nanoTime();
        Process kb =
                KbProcess.start(
                        KbProcess.jvm(Map.of()),
                        stub.url(),
                        HOOKS_ON,
                        workDir,
                        "hook",
                        "session-start");
        try (OutputStream open = kb.getOutputStream()) {
            open.write("{\"session_id\":".getBytes(StandardCharsets.UTF_8));
            open.flush();

            boolean exited = kb.waitFor(WITHIN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(exited).as("kb exited while stdin was still open").isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(WITHIN_BUDGET);
            assertThat(kb.exitValue()).isZero();
            assertThat(kb.getInputStream().readAllBytes()).isEmpty();
            assertThat(stub.requests).isEmpty();
        } finally {
            kb.destroyForcibly();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
