package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import io.github.ismetdyn.agentkb.client.TtyDriver.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What kb must do as a process, on the JVM (KbTerminalTest) and as the native binary (KbNativeIT,
 * plan.md H1): {@code kb tui} on a real terminal, {@code kb secret set} from a terminal and from a
 * pipe, and {@code kb run --file} cleaning up after SIGINT and SIGTERM. The values are fake.
 */
abstract class KbTerminalScenarios {

    static final String ID = "ornek-db/prod/app-password";
    static final String VALUE = "kbtest-FAKE-5e0b17c4";

    /**
     * docs/CLIENTS.md §3: the status line names the product; the stub answers no list, so the
     * screen shows that and stays.
     */
    private static final String SCREEN = "agentkb";

    /** Enter the alternate screen (xterm smcup). */
    private static final String ALT_SCREEN = "\u001b[?1049h";

    /**
     * Bracketed paste on and off (xterm 2004): the terminal wraps a paste in PASTE_START,
     * PASTE_END.
     */
    private static final String PASTE_ON = "\u001b[?2004h";

    private static final String PASTE_OFF = "\u001b[?2004l";
    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";

    private static final int SCREEN_SECONDS = 30;
    private static final int EXIT_SECONDS = 10;

    /** How long a key that is not q is given to (wrongly) end the TUI. */
    private static final int STAYS_SECONDS = 3;

    private static final long WAIT_SECONDS = 30;

    @TempDir Path workDir;

    private SecretStub server;

    /** The command that starts kb, before kb's own args. */
    abstract List<String> launcher();

    @BeforeEach
    void startServer() throws IOException {
        server = new SecretStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void tui_shows_its_screen_on_the_alternate_screen_and_q_quits() {
        Session session = tty(SCREEN, "q", EXIT_SECONDS, "tui");

        assertThat(session.status()).as(session.screen()).isEqualTo("exit=0");
        assertThat(session.screen()).contains(ALT_SCREEN).contains(PASTE_ON).contains(PASTE_OFF);
    }

    @Test
    void tui_with_no_alt_screen_draws_on_the_main_screen() {
        Session session = tty(SCREEN, "q", EXIT_SECONDS, "tui", "--no-alt-screen");

        assertThat(session.status()).as(session.screen()).isEqualTo("exit=0");
        assertThat(session.screen()).doesNotContain(ALT_SCREEN);
    }

    /**
     * A key that is not q, and a pasted q: with bracketed paste on, a paste is one PasteEvent,
     * which the list ignores, not a q key that would quit.
     */
    @Test
    void tui_keeps_running_on_a_key_that_is_not_q_and_on_a_pasted_q() {
        Session session = tty(SCREEN, "x" + PASTE_START + "q" + PASTE_END, STAYS_SECONDS, "tui");

        assertThat(session.status()).as(session.screen()).isEqualTo("timeout:exit");
        assertThat(session.screen()).contains(PASTE_ON);
    }

    /** docs/SECRETS.md §4: from a terminal the value is read without echo. */
    @Test
    void secret_set_reads_the_value_from_a_terminal_without_echo() {
        Session session = tty("Value for", VALUE + "\r", EXIT_SECONDS, "secret", "set", ID);

        assertThat(session.status()).as(session.screen()).isEqualTo("exit=0");
        assertThat(server.stored(ID)).isEqualTo(VALUE);
        assertThat(session.screen()).doesNotContain(VALUE);
    }

    @Test
    void secret_set_reads_the_value_from_a_pipe() {
        Result result =
                KbProcess.run(
                        launcher(),
                        server.url(),
                        Map.of(),
                        VALUE + "\n",
                        workDir,
                        "secret",
                        "set",
                        ID);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.stored(ID)).isEqualTo(VALUE);
        assertThat(result.stdout() + result.stderr()).doesNotContain(VALUE);
    }

    /**
     * docs/SECRETS.md §3: Ctrl-C (SIGINT) or a harness timeout (SIGTERM) still removes the {@code
     * --file} file and stops the child. A native image runs no shutdown hook on a signal unless
     * built with {@code --install-exit-handlers}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"INT", "TERM"})
    void a_signalled_kb_run_removes_the_file_and_stops_the_child(String signal) throws Exception {
        server.with(ID, VALUE);
        Path keyPath = workDir.resolve("keypath");
        Path childPid = workDir.resolve("childpid");
        Process kb =
                KbProcess.start(
                        launcher(),
                        server.url(),
                        Map.of(),
                        workDir,
                        KbProcess.runArgs(
                                List.of("--file", "KEY=kb:secret/" + ID),
                                "echo $$ > pid.tmp && mv pid.tmp childpid;"
                                        + " echo \"$KEY\" > key.tmp && mv key.tmp keypath;"
                                        + " exec sleep 60"));
        kb.getOutputStream().close();
        try {
            KbProcess.await(() -> Files.exists(keyPath), "child did not start");
            Path secretFile = Path.of(Files.readString(keyPath).strip());
            long pid = Long.parseLong(Files.readString(childPid).strip());
            assertThat(secretFile).as("file while the child runs").exists();

            Process kill =
                    new ProcessBuilder("kill", "-" + signal, Long.toString(kb.pid())).start();
            assertThat(kill.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).as("kill ended").isTrue();
            assertThat(kill.exitValue()).as("kill -" + signal).isZero();
            assertThat(kb.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).as("kb exited").isTrue();

            assertThat(secretFile).as("removed after SIG" + signal).doesNotExist();
            KbProcess.await(
                    () -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true),
                    "child still running after SIG" + signal + " to kb");
        } finally {
            kb.destroyForcibly();
        }
    }

    private Session tty(String waitFor, String keys, int exitSeconds, String... args) {
        return TtyDriver.drive(
                Stream.concat(launcher().stream(), Stream.of(args)).toList(),
                KbProcess.environment(server.url(), Map.of()),
                workDir,
                waitFor,
                keys,
                SCREEN_SECONDS,
                exitSeconds);
    }
}
