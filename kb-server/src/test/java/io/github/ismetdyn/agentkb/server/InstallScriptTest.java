package io.github.ismetdyn.agentkb.server;

import static io.github.ismetdyn.agentkb.server.InstallSandbox.AGENT_SETTINGS;
import static io.github.ismetdyn.agentkb.server.InstallSandbox.INTEGRATIONS;
import static io.github.ismetdyn.agentkb.server.InstallSandbox.SERVER_JAR_NAME;
import static io.github.ismetdyn.agentkb.server.InstallSandbox.UNITS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * integrations/install.sh, the one setup command on an empty machine (plan.md G14,
 * ~/.claude/rules/general.md "Taşınabilirlik"): installs the server jar as {@code
 * ~/kb-tools/agentkb-server.jar}, kb-hook.sh and AGENT-RULES.md into {@code ~/kb-tools/}
 * (integrations/claude-code/setup.md), the {@code kb} launcher as {@code ~/.local/bin/kb}, renders
 * agentkb.service, agentkb-sync.service and agentkb-sync.timer into {@code ~/.config/systemd/user/}
 * with the java it checked and no path of this checkout, runs {@code init}, then reloads systemd
 * and enables the service and the sync timer. The curator units wait for F4 ({@code kb curator}
 * does not exist in F2). A second run changes nothing; {@code ~/.claude} and {@code ~/.codex} are
 * never written (plan.md "Kapsam dışı"). Preconditions: InstallPreconditionsTest; the agent
 * settings switch: InstallDryRunTest. Sandbox: {@link InstallSandbox}.
 */
class InstallScriptTest {

    private static final Path CHECKOUT = INTEGRATIONS.getParent();

    @TempDir Path home;
    @TempDir Path tools;

    private InstallSandbox sandbox;

    @BeforeEach
    void sandbox() throws IOException {
        sandbox = new InstallSandbox(home, tools);
    }

    @Test
    void installs_tools_launcher_and_units_into_an_empty_home() throws IOException {
        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stderr()).isZero();
        Path kbTools = home.resolve("kb-tools");
        assertThat(kbTools.resolve(SERVER_JAR_NAME)).hasSameBinaryContentAs(sandbox.serverJar);
        assertThat(kbTools.resolve("kb-hook.sh"))
                .hasSameBinaryContentAs(INTEGRATIONS.resolve("kb-hook.sh"));
        assertThat(Files.getPosixFilePermissions(kbTools.resolve("kb-hook.sh")))
                .contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(kbTools.resolve("AGENT-RULES.md"))
                .hasSameBinaryContentAs(INTEGRATIONS.resolve("AGENT-RULES.md"));
        assertThat(Files.getPosixFilePermissions(home.resolve(".local/bin/kb")))
                .contains(PosixFilePermission.OWNER_EXECUTE);
        Path units = home.resolve(".config/systemd/user");
        for (String unit : UNITS) {
            assertThat(units.resolve(unit)).isRegularFile();
        }
        try (Stream<Path> installed = Files.list(units)) {
            assertThat(installed.map(unit -> unit.getFileName().toString()))
                    .as("the curator waits for F4")
                    .noneMatch(name -> name.contains("curator"));
        }
        assertThat(home.resolve(".claude")).doesNotExist();
        assertThat(home.resolve(".codex")).doesNotExist();
    }

    @Test
    void the_units_run_the_checked_java_and_hold_no_path_of_this_checkout() throws IOException {
        assertThat(sandbox.install().exit()).isZero();
        Path units = home.resolve(".config/systemd/user");

        List<String> server = execStart(units.resolve("agentkb.service"));

        assertThat(server.getFirst())
                .as("the java the version check passed")
                .isEqualTo(sandbox.stubBin.resolve("java").toString());
        int jar = server.indexOf("-jar");
        assertThat(jar).as("ExecStart %s", server).isNotNegative();
        assertThat(server.get(jar + 1).replace("%h", home.toString()))
                .isEqualTo(home.resolve("kb-tools").resolve(SERVER_JAR_NAME).toString());
        assertThat(
                        execStart(units.resolve("agentkb-sync.service"))
                                .getFirst()
                                .replace("%h", home.toString()))
                .isEqualTo(home.resolve(".local/bin/kb").toString());
        for (String unit : UNITS) {
            assertThat(Files.readString(units.resolve(unit), StandardCharsets.UTF_8))
                    .as(unit)
                    .doesNotContain(CHECKOUT.toString())
                    .doesNotContain(sandbox.serverJar.toString());
        }
    }

    /** The service needs the repo, so init runs before the service is enabled. */
    @Test
    void init_runs_before_the_service_and_the_sync_timer_are_enabled() throws IOException {
        assertThat(sandbox.install().exit()).isZero();
        String installedJar = home.resolve("kb-tools").resolve(SERVER_JAR_NAME).toString();
        Predicate<List<String>> enable =
                call ->
                        call.getFirst().equals("systemctl")
                                && call.containsAll(List.of("--user", "enable", "--now"));

        List<List<String>> calls = sandbox.calls();

        int init =
                indexOf(
                        calls,
                        call ->
                                call.getFirst().equals("java")
                                        && call.contains("-Duser.home=" + home)
                                        && call.contains(installedJar)
                                        && call.getLast().equals("init"));
        int reload =
                indexOf(
                        calls,
                        call ->
                                call.getFirst().equals("systemctl")
                                        && call.containsAll(List.of("--user", "daemon-reload")));
        int service =
                indexOf(
                        calls,
                        enable.and(
                                call ->
                                        call.contains("agentkb")
                                                || call.contains("agentkb.service")));
        int timer = indexOf(calls, enable.and(call -> call.contains("agentkb-sync.timer")));
        assertThat(List.of(init, reload, service, timer)).as("calls %s", calls).doesNotContain(-1);
        assertThat(init).isLessThan(service);
        assertThat(reload).isLessThan(service);
        assertThat(calls)
                .as("no curator unit enabled")
                .noneMatch(call -> call.stream().anyMatch(a -> a.contains("curator")));
    }

    @Test
    void the_kb_launcher_runs_the_installed_client_jar_with_its_arguments() throws IOException {
        assertThat(sandbox.install().exit()).isZero();
        Files.writeString(sandbox.log, "");

        Run kb = sandbox.run(List.of(home.resolve(".local/bin/kb").toString(), "check"));

        assertThat(kb.exit()).as(kb.stderr()).isZero();
        List<String> call =
                sandbox.calls().stream()
                        .filter(c -> c.getFirst().equals("java"))
                        .reduce((first, last) -> last)
                        .orElseThrow(() -> new AssertionError("kb did not run java"));
        assertThat(call.getLast()).isEqualTo("check");
        assertThat(call)
                .as("on Linux the JVM takes user.home from the password file, not from $HOME")
                .contains("-Duser.home=" + home);
        int jar = call.indexOf("-jar");
        assertThat(jar).as("java call %s", call).isPositive().isLessThan(call.size() - 1);
        assertThat(Path.of(call.get(jar + 1)))
                .as("the jar is an installed copy of the client jar")
                .startsWith(home)
                .hasSameBinaryContentAs(sandbox.clientJar);
    }

    @Test
    void a_second_install_changes_nothing() throws IOException {
        assertThat(sandbox.install().exit()).isZero();
        Map<String, String> first = sandbox.tree();

        Run second = sandbox.install();

        assertThat(second.exit()).as(second.stderr()).isZero();
        assertThat(sandbox.tree()).isEqualTo(first);
    }

    @Test
    void an_install_leaves_existing_agent_settings_untouched() throws IOException {
        sandbox.write(AGENT_SETTINGS);
        Map<String, String> before = sandbox.tree();

        assertThat(sandbox.install().exit()).isZero();

        Map<String, String> after = sandbox.tree();
        for (String file : AGENT_SETTINGS.keySet()) {
            assertThat(after.get(file)).as(file).isEqualTo(before.get(file));
        }
        assertThat(
                        after.keySet().stream()
                                .filter(f -> f.startsWith(".claude/") || f.startsWith(".codex/")))
                .as("no new file in ~/.claude or ~/.codex")
                .containsExactlyInAnyOrderElementsOf(AGENT_SETTINGS.keySet());
    }

    private static int indexOf(List<List<String>> calls, Predicate<List<String>> match) {
        for (int i = 0; i < calls.size(); i++) {
            if (match.test(calls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> execStart(Path unit) throws IOException {
        String line =
                Files.readAllLines(unit, StandardCharsets.UTF_8).stream()
                        .filter(l -> l.startsWith("ExecStart="))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no ExecStart in " + unit));
        return List.of(line.substring("ExecStart=".length()).strip().split("\\s+"));
    }
}
