package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * integrations/kb-hook.sh is a thin wrapper while installs move to {@code kb hook} (ADR-0027
 * decision 4): it hands its arguments and stdin to {@code kb hook} and prints its answer. It never
 * blocks a session: no kb, or a kb that fails to start, gives empty output and exit 0 (fail-open).
 * What {@code kb hook} sends and when it stays silent is KbHookTest's (kb-client). Runs the real
 * script with bash, kb a stub at {@code ~/.local/bin/kb}, where install.sh puts the launcher.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "a bash script of the Linux install")
class KbHookScriptTest {

    private static final Path SCRIPT = Path.of("..", "integrations", "kb-hook.sh");
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final String PAYLOAD = "{\"session_id\":\"s1\"}";
    // No kb on it: only the stub in ~/.local/bin can answer.
    private static final String PATH_WITHOUT_KB = "/usr/bin:/bin";

    @TempDir Path home;

    private record Run(int exit, String stdout) {}

    @Test
    void the_script_hands_arguments_and_stdin_to_kb_hook_and_prints_its_answer() throws Exception {
        kb(
                """
                #!/bin/sh
                printf '%s\\n' "$@" > "$HOME/args"
                cat > "$HOME/stdin"
                printf '{"answer":true}'
                """);

        Run run = run("session-start", "codex");

        assertThat(run).isEqualTo(new Run(0, "{\"answer\":true}"));
        assertThat(Files.readAllLines(home.resolve("args")))
                .containsExactly("hook", "session-start", "codex");
        assertThat(home.resolve("stdin")).hasContent(PAYLOAD);
    }

    @Test
    void without_kb_the_script_gives_empty_output_and_exit_0() throws Exception {
        assertThat(run("session-start", "claude")).isEqualTo(new Run(0, ""));
    }

    @Test
    void a_kb_that_fails_still_gives_exit_0() throws Exception {
        kb(
                """
                #!/bin/sh
                echo "java: not found" >&2
                exit 127
                """);

        assertThat(run("user-prompt-submit", "claude")).isEqualTo(new Run(0, ""));
    }

    private void kb(String script) throws IOException {
        Path kb = Files.createDirectories(home.resolve(".local/bin")).resolve("kb");
        Files.writeString(kb, script);
        Files.setPosixFilePermissions(kb, PosixFilePermissions.fromString("rwx------"));
    }

    private Run run(String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString());
        builder.command().addAll(List.of(args));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PATH", PATH_WITHOUT_KB);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        // From a file, not a pipe: a script that exits before reading would break the pipe.
        builder.redirectInput(Files.writeString(home.resolve("payload.json"), PAYLOAD).toFile());
        Process script = builder.start();
        String stdout = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(script.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new Run(script.exitValue(), stdout);
    }
}
