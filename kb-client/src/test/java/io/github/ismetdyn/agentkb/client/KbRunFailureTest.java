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

/**
 * docs/SECRETS.md §3: when a value cannot be had, {@code kb run} does not start the command, exits
 * non-zero and says why in one clear line; the command would leave a file named {@code ran}.
 */
class KbRunFailureTest {

    private static final String MISSING_ID = "ornek-lb/prod/panel-admin";
    private static final String TOUCH_RAN = "touch ran";
    private static final List<String> INTERNAL_DETAIL = List.of("Exception", "\tat ");

    @TempDir Path workDir;

    /** The value was set on another machine: the Secret is known, its value is not here. */
    @Test
    void a_missing_value_stops_the_command_and_names_the_id() throws IOException {
        try (SecretStub server = new SecretStub().with(ID, VALUE)) {
            Result result =
                    KbProcess.run(
                            server.url(),
                            "",
                            workDir,
                            runArgs(
                                    List.of(
                                            "--env",
                                            "PASS=kb:secret/" + ID,
                                            "--env",
                                            "ADMIN=kb:secret/" + MISSING_ID),
                                    TOUCH_RAN));

            assertThat(ran(workDir)).as("the command ran").isFalse();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("kb secret set " + MISSING_ID);
            assertThat(result.stderr() + result.stdout())
                    .doesNotContain(VALUE)
                    .doesNotContain(INTERNAL_DETAIL);
        }
    }

    /**
     * An environment value cannot hold a NUL; the JDK's refusal quotes the whole value, so kb must
     * refuse first and name only the id.
     */
    @Test
    void a_value_with_a_nul_byte_is_refused_for_env_without_showing_it() throws IOException {
        String head = "kbtest-FAKE-nul-head";
        String tail = "kbtest-FAKE-nul-tail";
        try (SecretStub server = new SecretStub().with(ID, head + '\u0000' + tail)) {
            Result result =
                    KbProcess.run(
                            server.url(),
                            "",
                            workDir,
                            runArgs(List.of("--env", "PASS=kb:secret/" + ID), TOUCH_RAN));

            assertThat(ran(workDir)).as("the command ran").isFalse();
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains(ID).contains("--file");
            assertThat(result.stderr() + result.stdout())
                    .doesNotContain(head)
                    .doesNotContain(tail)
                    .doesNotContain(INTERNAL_DETAIL);
        }
    }

    @Test
    void a_server_that_is_down_stops_the_command_with_a_clear_error() throws IOException {
        String downUrl = "http://127.0.0.1:" + closedPort();

        Result result =
                KbProcess.run(
                        downUrl,
                        "",
                        workDir,
                        runArgs(List.of("--env", "PASS=kb:secret/" + ID), TOUCH_RAN));

        assertThat(ran(workDir)).as("the command ran").isFalse();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).as("names where it looked").contains(downUrl);
        assertThat(result.stderr()).doesNotContain(INTERNAL_DETAIL);
    }

    /**
     * {@code --env} takes a placeholder; a plain value would put the secret on the command line.
     */
    @Test
    void an_option_that_is_not_a_placeholder_is_refused() throws IOException {
        try (SecretStub server = new SecretStub().with(ID, VALUE)) {
            Result result =
                    KbProcess.run(
                            server.url(),
                            "",
                            workDir,
                            runArgs(List.of("--env", "PASS=" + VALUE), TOUCH_RAN));

            assertThat(ran(workDir)).as("the command ran").isFalse();
            assertThat(result.exitCode()).isNotZero();
            assertThat(server.requests()).isEmpty();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
