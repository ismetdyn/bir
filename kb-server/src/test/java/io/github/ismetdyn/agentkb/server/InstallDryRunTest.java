package io.github.ismetdyn.agentkb.server;

import static io.github.ismetdyn.agentkb.server.InstallSandbox.AGENT_SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code integrations/install.sh --dry-run} (plan.md "Kapsam dışı", ROADMAP F2 "AGENT-RULES
 * devreye"): writes nothing and runs nothing, and prints the exact changes the later, user-approved
 * switch would make to {@code ~/.claude/settings.json} hooks, {@code ~/.claude/CLAUDE.md}, {@code
 * ~/.codex/config.toml} and {@code ~/.codex/hooks.json} (integrations/claude-code/setup.md,
 * integrations/codex/setup.md), with {@code kb import-memories} before built-in memory is turned
 * off (Claude Code {@code autoMemoryEnabled}, code.claude.com settings docs, 2026-11-06). Sandbox:
 * {@link InstallSandbox}.
 */
class InstallDryRunTest {

    @TempDir Path home;
    @TempDir Path tools;

    private InstallSandbox sandbox;

    @BeforeEach
    void sandbox() throws IOException {
        sandbox = new InstallSandbox(home, tools);
    }

    @Test
    void dry_run_writes_nothing_and_prints_the_agent_settings_switch() throws IOException {
        sandbox.write(AGENT_SETTINGS);
        Map<String, String> before = sandbox.tree();

        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(sandbox.tree()).as("nothing written").isEqualTo(before);
        assertThat(sandbox.calls())
                .as("nothing run: no init, no systemctl")
                .noneMatch(call -> call.getFirst().equals("systemctl") || call.contains("-jar"));
        String out = run.stdout();
        assertThat(out)
                .contains(".claude/settings.json", ".claude/CLAUDE.md")
                .contains(".codex/config.toml", ".codex/hooks.json")
                .contains("$HOME/.local/bin/kb hook session-start claude")
                .contains("$HOME/.local/bin/kb hook user-prompt-submit claude")
                .contains("$HOME/.local/bin/kb hook post-tool-use claude")
                .contains("$HOME/.local/bin/kb hook session-start codex")
                .contains("$HOME/.local/bin/kb hook user-prompt-submit codex")
                .contains("$HOME/.local/bin/kb hook post-tool-use codex")
                .contains("AskUserQuestion", "request_user_input")
                .contains("[mcp_servers.kb]")
                .contains("AGENT-RULES")
                .contains("autoMemoryEnabled");
        assertThat(out.indexOf("kb import-memories"))
                .as("memories are imported before built-in memory is turned off")
                .isNotNegative()
                .isLessThan(out.indexOf("autoMemoryEnabled"));
    }

    @Test
    void dry_run_in_an_empty_home_creates_nothing() throws IOException {
        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(sandbox.tree()).isEmpty();
    }
}
