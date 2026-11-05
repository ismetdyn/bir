package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/SECRETS.md §3: {@code kb run} resolves {@code kb:secret/<id>} placeholders through {@code
 * GET /api/v1/secrets/<id>}, gives the values only to the child (environment, or a 0600 file for
 * {@code --file}) and returns the child's exit code. Masking: KbRunMaskingTest. The values are
 * fake.
 */
class KbRunTest {

    static final String ID = "ornek-db/prod/app-password";
    static final String VALUE = "kbtest-FAKE/p@ss w+rd:7f3a9c";
    private static final String OTHER_ID = "ornek-app-01/prod/deploy-ssh-key";
    private static final String OTHER_VALUE = "kbtest-FAKE-other-51d84b06";
    private static final long WAIT_SECONDS = 30;
    private static final long POLL_MILLIS = 50;

    @TempDir Path workDir;

    private SecretStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new SecretStub().with(ID, VALUE).with(OTHER_ID, OTHER_VALUE);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void every_placeholder_reaches_the_child_as_its_value() {
        Result result =
                kbRun(
                        List.of(
                                "--env",
                                "PASS=kb:secret/" + ID,
                                "--env",
                                "KEY=kb:secret/" + OTHER_ID),
                        "if [ \"$PASS\" = \"$1\" ] && [ \"$KEY\" = \"$2\" ]; then echo same;"
                                + " else echo differ; fi",
                        VALUE,
                        OTHER_VALUE);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("same\n");
    }

    @Test
    void the_exit_code_of_the_child_is_returned() {
        Result result = kbRun(List.of("--env", "PASS=kb:secret/" + ID), "exit 7");

        assertThat(result.exitCode()).isEqualTo(7);
    }

    /**
     * {@code --file}: a 0600 file holding the value, its path in the variable, removed after the
     * child, also when the child fails.
     */
    @Test
    void a_file_placeholder_gives_a_private_temporary_file_that_is_removed_after() {
        Result result =
                kbRun(
                        List.of("--file", "KEY=kb:secret/" + OTHER_ID),
                        "echo \"$KEY\"; stat -c %a \"$KEY\";"
                                + " if [ \"$(cat \"$KEY\")\" = \"$1\" ]; then echo same; fi; exit 3",
                        OTHER_VALUE);

        List<String> lines = result.stdout().lines().toList();
        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(3);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).as("file mode").isEqualTo("600");
        assertThat(lines.get(2)).isEqualTo("same");
        assertThat(Path.of(lines.get(0))).as("removed after the child").doesNotExist();
    }

    /**
     * SIGTERM to kb (an agent harness timeout) still removes the {@code --file} file and stops the
     * child, which would otherwise keep the value in its environment.
     */
    @Test
    void a_terminated_kb_removes_the_file_and_stops_the_child() throws Exception {
        Path keyPath = workDir.resolve("keypath");
        Path childPid = workDir.resolve("childpid");
        Process kb =
                KbProcess.start(
                        server.url(),
                        workDir,
                        KbProcess.runArgs(
                                List.of("--file", "KEY=kb:secret/" + OTHER_ID),
                                "echo $$ > pid.tmp && mv pid.tmp childpid;"
                                        + " echo \"$KEY\" > key.tmp && mv key.tmp keypath;"
                                        + " exec sleep 60"));
        kb.getOutputStream().close();
        try {
            await(() -> Files.exists(keyPath), "child did not start");
            Path secretFile = Path.of(Files.readString(keyPath).strip());
            long pid = Long.parseLong(Files.readString(childPid).strip());
            assertThat(secretFile).as("file while the child runs").exists();

            kb.destroy(); // SIGTERM
            assertThat(kb.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).as("kb exited").isTrue();

            assertThat(secretFile).as("removed after SIGTERM").doesNotExist();
            await(
                    () -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true),
                    "child still running after kb was terminated");
        } finally {
            kb.destroyForcibly();
        }
    }

    private static void await(BooleanSupplier condition, String failure)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(failure);
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    /** ADR-0020: no token; the client is no browser either. Ids keep their slashes in the path. */
    @Test
    void requests_carry_no_authorization_and_no_origin_and_keep_the_id_path() {
        kbRun(List.of("--env", "PASS=kb:secret/" + ID), "true");

        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("GET");
                            assertThat(request.rawPath()).isEqualTo(SecretStub.PREFIX + ID);
                            assertThat(request.headers().keySet())
                                    .noneMatch("Authorization"::equalsIgnoreCase)
                                    .noneMatch("Origin"::equalsIgnoreCase);
                        });
    }

    private Result kbRun(List<String> options, String script, String... scriptArgs) {
        return KbProcess.run(
                server.url(), "", workDir, KbProcess.runArgs(options, script, scriptArgs));
    }
}
