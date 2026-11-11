package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * integrations/install.sh with a native kb (plan.md H9, ADR-0025 decision 7): when one is given it
 * is installed as {@code ~/kb-tools/kb} instead of the client jar, and {@code ~/.local/bin/kb} runs
 * it with {@code -Duser.home=$HOME} (a native image, like the JVM, takes user.home from the
 * password file). Without one the jar launcher stays (InstallScriptTest). The dry run names the
 * variant and writes nothing; a second run changes nothing. Sandbox: {@link InstallSandbox}.
 */
class InstallNativeTest {

    @TempDir Path home;
    @TempDir Path tools;

    private InstallSandbox sandbox;

    @BeforeEach
    void sandbox() throws IOException {
        sandbox = new InstallSandbox(home, tools);
    }

    private Path useNativeKb() {
        sandbox.clientNative = sandbox.nativeKb.toString();
        return sandbox.nativeKb;
    }

    @Test
    void a_native_kb_is_installed_instead_of_the_client_jar() throws IOException {
        Path nativeKb = useNativeKb();

        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stderr()).isZero();
        Path installed = home.resolve("kb-tools/kb");
        assertThat(installed).hasSameBinaryContentAs(nativeKb);
        assertThat(Files.getPosixFilePermissions(installed))
                .contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(home.resolve("kb-tools/agentkb-client.jar")).doesNotExist();
        assertThat(run.stdout()).contains("(native)");
    }

    @Test
    void the_kb_launcher_runs_the_native_kb_with_the_given_home_and_no_java() throws IOException {
        useNativeKb();
        assertThat(sandbox.install().exit()).isZero();
        Files.writeString(sandbox.log, "");

        Run kb = sandbox.run(List.of(home.resolve(".local/bin/kb").toString(), "check"));

        assertThat(kb.exit()).as(kb.stderr()).isZero();
        List<List<String>> calls = sandbox.calls();
        assertThat(calls).as("calls %s", calls).hasSize(1);
        assertThat(calls.getFirst()).containsExactly("kb-native", "-Duser.home=" + home, "check");
    }

    @Test
    void a_native_install_needs_no_client_jar() throws IOException {
        useNativeKb();
        Files.delete(sandbox.clientJar);

        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stderr()).isZero();
    }

    @Test
    void a_second_native_install_changes_nothing() throws IOException {
        useNativeKb();
        assertThat(sandbox.install().exit()).isZero();
        Map<String, String> first = sandbox.tree();
        Files.writeString(sandbox.log, "");

        Run second = sandbox.install();

        assertThat(second.exit()).as(second.stderr()).isZero();
        assertThat(sandbox.tree()).isEqualTo(first);
        assertThat(sandbox.calls())
                .as("nothing changed, so the running server is not restarted")
                .noneMatch(call -> call.contains("try-restart"));
    }

    @Test
    void switching_the_variant_switches_the_launcher_and_removes_the_other_client()
            throws IOException {
        Path jar = home.resolve("kb-tools/agentkb-client.jar");
        Path nativeKb = home.resolve("kb-tools/kb");
        assertThat(sandbox.install().exit()).isZero();
        assertThat(jar).exists();
        useNativeKb();
        assertThat(sandbox.install().exit()).isZero();
        Files.writeString(sandbox.log, "");

        Run kb = sandbox.run(List.of(home.resolve(".local/bin/kb").toString(), "check"));

        assertThat(kb.exit()).as(kb.stderr()).isZero();
        assertThat(sandbox.calls()).extracting(List::getFirst).containsExactly("kb-native");
        assertThat(jar).as("the jar the native kb replaced").doesNotExist();

        sandbox.clientNative = "";
        assertThat(sandbox.install().exit()).isZero();

        assertThat(jar).exists();
        assertThat(nativeKb).as("the native kb the jar replaced").doesNotExist();
    }

    @Test
    void dry_run_names_the_native_kb_and_writes_nothing() throws IOException {
        Path nativeKb = useNativeKb();

        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(sandbox.tree()).isEmpty();
        assertThat(run.stdout())
                .contains("kb client: native")
                .contains(nativeKb + " -> " + home.resolve("kb-tools/kb"));
        assertThat(sandbox.calls())
                .as("nothing run but the java version check")
                .allMatch(call -> call.getFirst().equals("java") && !call.contains("-jar"));
    }

    @Test
    void dry_run_without_a_native_kb_names_the_jar_launcher() throws IOException {
        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(sandbox.tree()).isEmpty();
        assertThat(run.stdout())
                .contains("kb client: jar")
                .contains(sandbox.clientJar + " -> " + home.resolve("kb-tools/agentkb-client.jar"));
    }

    @Test
    void a_given_native_kb_that_is_missing_stops_before_any_change() throws IOException {
        Files.delete(sandbox.nativeKb);
        sandbox.clientNative = sandbox.nativeKb.toString();

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains("native kb", sandbox.nativeKb.toString());
        assertThat(sandbox.tree()).isEmpty();
    }
}
