package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.picocli.TuiCommand;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.ismetdyn.agentkb.client.KbApi;
import picocli.CommandLine.Command;

/**
 * {@code kb tui} (docs/CLIENTS.md §3, ADR-0025 decision 8): TuiCommand sets up and restores the
 * terminal and brings the {@code --no-alt-screen} option; TuiLoop runs the screens against the
 * server of {@code AGENTKB_URL}, waiting at most KbApi.INTERACTIVE for an answer. Ctrl+C, and q
 * outside a text field, quit.
 */
@Command(name = "tui", description = "Open the terminal interface.")
public final class KbTuiCommand extends TuiCommand {

    /**
     * The options' config with bracketed paste on (TamboUI 0.6.0 leaves it off): a paste then comes
     * as one PasteEvent, so a pasted q or newline is text, not the quit key or Enter.
     */
    @Override
    protected TuiConfig createConfig() {
        return super.createConfig().toBuilder().bracketedPaste(true).build();
    }

    @Override
    protected void runTui(TuiRunner runner) throws Exception {
        try (TuiLoop loop = new TuiLoop(KbApi.fromEnvironment(KbApi.INTERACTIVE))) {
            loop.start(runner);
            runner.run(loop, loop);
        }
    }
}
