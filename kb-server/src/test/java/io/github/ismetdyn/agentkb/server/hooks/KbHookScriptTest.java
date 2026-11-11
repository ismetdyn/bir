package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * integrations/kb-hook.sh is a thin wrapper while installs move to {@code kb hook} (ADR-0027
 * decision 4): it hands its arguments and stdin to {@code ~/.local/bin/kb hook}, where install.sh
 * puts the launcher, and prints its answer; never a kb from the PATH. It never blocks a session:
 * hooks off, no kb, or a kb that fails to start give empty output and exit 0 (fail-open). What
 * {@code kb hook} sends and when it stays silent is KbHookTest's (kb-client). Runs the real script
 * with bash, kb a stub.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "a bash script of the Linux install")
class KbHookScriptTest {

    private static final Path SCRIPT = Path.of("..", "integrations", "kb-hook.sh");
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final String PAYLOAD = "{\"session_id\":\"s1\"}";
    // No kb on it: only the stub in ~/.local/bin can answer.
    private static final String PATH_WITHOUT_KB = "/usr/bin:/bin";
    private static final String RECORDING_KB =
            """
            #!/bin/sh
            printf '%s\\n' "$@" > "$HOME/args"
            cat > "$HOME/stdin"
            printf '{"answer":true}'
            """;

    @TempDir Path home;

    private record Run(int exit, String stdout) {}

    @Test
    void the_script_hands_arguments_and_stdin_to_kb_hook_and_prints_its_answer() throws Exception {
        kb(RECORDING_KB);

        Run run = run(Map.of(), "session-start", "codex");

        assertThat(run).isEqualTo(new Run(0, "{\"answer\":true}"));
        assertThat(Files.readAllLines(home.resolve("args")))
                .containsExactly("hook", "session-start", "codex");
        assertThat(home.resolve("stdin")).hasContent(PAYLOAD);
    }

    @Test
    void without_kb_the_script_gives_empty_output_and_exit_0() throws Exception {
        assertThat(run(Map.of(), "session-start", "claude")).isEqualTo(new Run(0, ""));
    }

    /** Review S1: an agent's hook runs in any directory, so the PATH could name a planted kb. */
    @Test
    void a_kb_on_the_path_is_never_used() throws Exception {
        Path planted = home.resolve("planted");
        kb(planted, RECORDING_KB);

        Run run = run(Map.of("PATH", planted + ":" + PATH_WITHOUT_KB), "session-start", "claude");

        assertThat(run).isEqualTo(new Run(0, ""));
        assertThat(home.resolve("args")).doesNotExist();
    }

    /** Hooks switched off (the curator's sessions) never start kb, a JVM on every prompt. */
    @Test
    void hooks_off_exit_before_kb_starts() throws Exception {
        kb(RECORDING_KB);

        Run run = run(Map.of("KB_HOOKS", "off"), "user-prompt-submit", "claude");

        assertThat(run).isEqualTo(new Run(0, ""));
        assertThat(home.resolve("args")).doesNotExist();
    }

    @Test
    void a_kb_that_fails_still_gives_exit_0() throws Exception {
        kb(
                """
                #!/bin/sh
                echo "java: not found" >&2
                exit 127
                """);

        assertThat(run(Map.of(), "user-prompt-submit", "claude")).isEqualTo(new Run(0, ""));
    }

    private void kb(String script) throws IOException {
        kb(home.resolve(".local/bin"), script);
    }

    private static void kb(Path dir, String script) throws IOException {
        Path kb = Files.createDirectories(dir).resolve("kb");
        Files.writeString(kb, script);
        Files.setPosixFilePermissions(kb, PosixFilePermissions.fromString("rwx------"));
    }

    private Run run(Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString());
        builder.command().addAll(List.of(args));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PATH", PATH_WITHOUT_KB);
        builder.environment().remove("KB_HOOKS");
        builder.environment().putAll(env);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        // From a file, not a pipe: a script that exits before reading would break the pipe.
        builder.redirectInput(Files.writeString(home.resolve("payload.json"), PAYLOAD).toFile());
        Process script = builder.start();
        String stdout = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(script.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new Run(script.exitValue(), stdout);
    }
}
