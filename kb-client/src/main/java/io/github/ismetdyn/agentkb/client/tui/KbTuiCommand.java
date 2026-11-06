package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.picocli.TuiCommand;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import picocli.CommandLine.Command;

/**
 * {@code kb tui} (docs/CLIENTS.md, ADR-0025 decision 8): TuiCommand sets up and restores the
 * terminal and brings the {@code --no-alt-screen} option. Its runner is a plain TuiRunner, so the
 * toolkit screen is drawn here and the quit key (q, Ctrl+C) is handled here, as ToolkitRunner
 * would.
 */
@Command(name = "tui", description = "Open the terminal interface.")
public final class KbTuiCommand extends TuiCommand {

    @Override
    protected void runTui(TuiRunner runner) throws Exception {
        runner.run(
                (event, tui) -> {
                    if (event instanceof KeyEvent key && key.isQuit()) {
                        tui.quit();
                    }
                    return false;
                },
                frame -> HelloScreen.view().render(frame, frame.area(), RenderContext.empty()));
    }
}
