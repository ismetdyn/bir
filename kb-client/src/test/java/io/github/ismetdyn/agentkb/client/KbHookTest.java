package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code kb hook <event> [client]} (ADR-0027 decision 4) carries a Claude Code or Codex hook to
 * {@code POST /hooks/<event>?client=} exactly as integrations/kb-hook.sh did with curl: stdin as
 * the JSON body, the server's answer on stdout. It never blocks a session: server down, slow,
 * failing, hooks off, an unknown event or an address off this machine give no output and exit 0
 * (fail-open) within the 2 s budget.
 */
class KbHookTest {

    private static final String ANSWER =
            "{\"hookSpecificOutput\":{\"additionalContext\":\"brief: ornek-proje\"}}";
    private static final String PAYLOAD = "{\"session_id\":\"s1\",\"cwd\":\"/tmp/x\"}";
    private static final Map<String, String> HOOKS_ON = Map.of("KB_HOOKS", "on");
    private static final Result SILENT = new Result(0, "", "");
    // The 2 s budget plus a JVM start on a loaded test machine; far below the stub's delay.
    private static final Duration WITHIN_BUDGET = Duration.ofSeconds(6);
    private static final Duration STUB_DELAY = Duration.ofSeconds(20);
    private static final int STUB_WAIT_MILLIS = 200;

    @TempDir Path workDir;

    private record Request(String method, String path, String query, String type, String body) {}

    /** The server side of /hooks/, recording every request. */
    private static final class HookStub implements AutoCloseable {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        private final HttpServer server;
        private final int status;
        private final Duration delay;

        HookStub(int status, Duration delay) throws IOException {
            this.status = status;
            this.delay = delay;
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hooks/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                requests.add(
                        new Request(
                                exchange.getRequestMethod(),
                                exchange.getRequestURI().getRawPath(),
                                exchange.getRequestURI().getRawQuery(),
                                exchange.getRequestHeaders().getFirst("Content-Type"),
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8)));
                Thread.sleep(delay);
                byte[] body = ANSWER.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

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
                        new Request(
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

        assertThat(stub.requests).extracting(Request::query).containsExactly("client=claude");
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

    @Test
    void hooks_off_an_unknown_event_or_no_event_send_nothing() throws IOException {
        stub = new HookStub(200, Duration.ZERO);

        assertThat(
                        KbProcess.run(
                                stub.url(),
                                Map.of("KB_HOOKS", "off"),
                                PAYLOAD,
                                workDir,
                                "hook",
                                "session-start"))
                .isEqualTo(SILENT);
        assertThat(KbProcess.run(stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook", "stop"))
                .isEqualTo(SILENT);
        assertThat(KbProcess.run(stub.url(), HOOKS_ON, PAYLOAD, workDir, "hook")).isEqualTo(SILENT);
        assertThat(stub.requests).isEmpty();
    }

    /**
     * Invariants 3 and 4, ADR-0020: the hook carries prompts and answers, so it talks to this
     * machine only, never through a proxy from the environment.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "http://0.0.0.0:%d",
                "http://127.0.0.1:%d@0.0.0.0:%d",
                "http://127.0.0.1.%d.example:%d",
                "http://localhost.example:%d"
            })
    void an_address_off_this_machine_gets_no_request(String url) throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            String target = url.replace("%d", String.valueOf(listener.getLocalPort()));
            String proxy = "http://127.0.0.1:" + listener.getLocalPort();

            Result result =
                    KbProcess.run(
                            target,
                            Map.of("KB_HOOKS", "on", "http_proxy", proxy, "all_proxy", proxy),
                            PAYLOAD,
                            workDir,
                            "hook",
                            "session-start");

            assertThat(result).isEqualTo(SILENT);
            assertThat(connected(listener)).as(target).isFalse();
        }
    }

    /** A connection kb made waits in the backlog even after kb has ended. */
    private static boolean connected(ServerSocket listener) throws IOException {
        listener.setSoTimeout(STUB_WAIT_MILLIS);
        try (Socket ignored = listener.accept()) {
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
}
