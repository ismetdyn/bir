package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What {@code kb hook} never does (ADR-0027 decision 4, invariants 3 and 4, ADR-0020): send while
 * hooks are off or for an unknown event, talk to an address off this machine, or go through a
 * proxy, even one the JVM is told to use for every host. Each gives no output and exit 0.
 */
class KbHookSafetyTest {

    private static final String ANSWER = HookStub.ANSWER;
    private static final String PAYLOAD = "{\"session_id\":\"s1\",\"cwd\":\"/tmp/x\"}";
    private static final Map<String, String> HOOKS_ON = Map.of("KB_HOOKS", "on");
    private static final Result SILENT = new Result(0, "", "");
    private static final int STUB_WAIT_MILLIS = 200;

    @TempDir Path workDir;

    private HookStub stub;

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.close();
        }
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
     * A proxy the JVM is told to use for every host, loopback included, gets nothing: the request
     * goes straight to the server (the curl script's {@code --noproxy '*'}).
     */
    @Test
    void a_proxy_from_the_jvm_options_is_not_used() throws IOException {
        stub = new HookStub(200, Duration.ZERO);
        try (ServerSocket proxy = new ServerSocket(0)) {
            List<String> launcher = new ArrayList<>(KbProcess.jvm(Map.of()));
            launcher.addAll(
                    1,
                    List.of(
                            "-Dhttp.proxyHost=127.0.0.1",
                            "-Dhttp.proxyPort=" + proxy.getLocalPort(),
                            "-Dhttp.nonProxyHosts="));

            Result result =
                    KbProcess.run(
                            launcher,
                            stub.url(),
                            HOOKS_ON,
                            PAYLOAD,
                            workDir,
                            "hook",
                            "session-start");

            assertThat(connected(proxy)).as("the proxy").isFalse();
            assertThat(result).isEqualTo(new Result(0, ANSWER, ""));
            assertThat(stub.requests).hasSize(1);
        }
    }

    /**
     * Invariants 3 and 4, ADR-0020: the hook carries prompts and answers, so it talks to this
     * machine only.
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

            Result result =
                    KbProcess.run(target, HOOKS_ON, PAYLOAD, workDir, "hook", "session-start");

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
}
