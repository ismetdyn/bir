package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.KbRunTest.ID;
import static io.github.ismetdyn.agentkb.client.KbRunTest.VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0027 decision 3: Windows has no SIGTERM, so a {@code kb run} killed there runs no shutdown
 * hook and its {@code --file} file stays. The file name carries the pid of the kb that wrote it;
 * the next {@code kb run} removes the files whose kb is gone and leaves every other file alone.
 */
class KbRunSweepTest {

    private static final long DEAD = 4_000_001L;
    private static final long ALIVE = 4_000_002L;

    @TempDir Path dir;

    @Test
    void the_sweep_removes_only_the_files_of_a_kb_that_is_gone() throws IOException {
        Path orphan = Files.createFile(dir.resolve("kb-secret-" + DEAD + "-123"));
        Path running = Files.createFile(dir.resolve("kb-secret-" + ALIVE + "-456"));
        // Without a pid (an older kb's name) or no kb file at all: never touched.
        Path older = Files.createFile(dir.resolve("kb-secret-" + DEAD));
        Path other = Files.createFile(dir.resolve("kb-secret-notes.txt"));
        Path unrelated = Files.createFile(dir.resolve("other-" + DEAD + "-1"));

        RunCommand.sweep(dir, Set.of(ALIVE)::contains);

        assertThat(orphan).doesNotExist();
        assertThat(running).exists();
        assertThat(older).exists();
        assertThat(other).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void a_missing_directory_is_nothing_to_sweep() {
        RunCommand.sweep(dir.resolve("missing"), pid -> false);

        assertThat(dir.resolve("missing")).doesNotExist();
    }

    /**
     * End to end on the Windows test machine (W0): kb killed with TerminateProcess leaves its file;
     * the next kb run removes it.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_next_run_removes_the_file_of_a_killed_run() throws Exception {
        try (SecretStub server = new SecretStub().with(ID, VALUE)) {
            List<String> file = List.of("--file", "KEY=kb:secret/" + ID);
            Process killed =
                    KbProcess.start(
                            KbProcess.jvm(Map.of()),
                            server.url(),
                            Map.of(),
                            dir,
                            run(
                                    file,
                                    "Write-Output $env:KEY; [Console]::Out.Flush();"
                                            + " Start-Sleep -Seconds 30"));
            Path left;
            try (BufferedReader out =
                    new BufferedReader(
                            new InputStreamReader(
                                    killed.getInputStream(), StandardCharsets.UTF_8))) {
                left = Path.of(out.readLine().strip());
                killed.destroyForcibly().waitFor();
            }
            assertThat(left).as("no shutdown hook ran").exists();

            Result next = KbProcess.run(server.url(), "", dir, run(file, "Write-Output $env:KEY"));

            assertThat(next.exitCode()).as(next.stderr()).isZero();
            assertThat(left).doesNotExist();
        }
    }

    private static String[] run(List<String> options, String script) {
        List<String> args = new ArrayList<>(List.of("run"));
        args.addAll(options);
        args.addAll(List.of("--", "powershell", "-NoProfile", "-Command", script));
        return args.toArray(String[]::new);
    }
}
