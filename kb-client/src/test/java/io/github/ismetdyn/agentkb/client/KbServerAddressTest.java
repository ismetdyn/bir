package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.KbProcess.ran;
import static io.github.ismetdyn.agentkb.client.KbProcess.runArgs;
import static io.github.ismetdyn.agentkb.client.KbRunTest.ID;
import static io.github.ismetdyn.agentkb.client.KbRunTest.VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0020 and docs/SECRETS.md: the server runs only on loopback and a value never leaves this
 * machine, so kb refuses an {@code AGENTKB_URL} whose host is not 127.0.0.0/8, ::1 or localhost
 * before it sends anything.
 */
class KbServerAddressTest {

    private static final String REFUSAL = "AGENTKB_URL must name this machine";
    private static final String TOUCH_RAN = "touch ran";

    @TempDir Path workDir;

    /**
     * On Linux 0.0.0.0 reaches a loopback listener, so the stub sees whatever kb would send to a
     * host that is not a loopback address.
     */
    private static String viaAnyAddress(SecretStub server) {
        return server.url().replace("127.0.0.1", "0.0.0.0");
    }

    @Test
    void run_sends_nothing_to_a_host_that_is_not_loopback() throws IOException {
        try (SecretStub server = new SecretStub().with(ID, VALUE)) {
            Result result =
                    KbProcess.run(
                            viaAnyAddress(server),
                            "",
                            workDir,
                            runArgs(List.of("--env", "PASS=kb:secret/" + ID), TOUCH_RAN));

            assertThat(server.requests()).isEmpty();
            assertThat(ran(workDir)).as("the command ran").isFalse();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains(REFUSAL);
        }
    }

    @Test
    void secret_set_sends_no_value_to_a_host_that_is_not_loopback() throws IOException {
        try (SecretStub server = new SecretStub()) {
            Result result =
                    KbProcess.run(
                            viaAnyAddress(server), VALUE + "\n", workDir, "secret", "set", ID);

            assertThat(server.requests()).isEmpty();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains(REFUSAL);
            assertThat(result.stdout() + result.stderr()).doesNotContain(VALUE);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://192.0.2.1:7411",
                "http://127.0.0.1@192.0.2.1:7411",
                "http://127.0.0.1.example.invalid:7411",
                "http://localhost.example.invalid:7411",
                "http://[2001:db8::1]:7411",
                "not a url"
            })
    void a_host_that_is_not_loopback_is_refused(String url) {
        Result result = KbProcess.run(url, "", workDir, "secret", "show", ID);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains(REFUSAL);
    }

    /** Accepted forms reach the connect step: nothing listens there, so kb cannot reach it. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:%d",
                "http://127.8.9.10:%d",
                "http://localhost:%d",
                "http://LocalHost:%d/",
                "http://[::1]:%d"
            })
    void a_loopback_host_is_accepted(String pattern) throws IOException {
        String url = pattern.formatted(closedPort());

        Result result = KbProcess.run(url, "", workDir, "secret", "show", ID);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("cannot reach").doesNotContain(REFUSAL);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
