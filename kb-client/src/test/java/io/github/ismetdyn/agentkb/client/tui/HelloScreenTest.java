package io.github.ismetdyn.agentkb.client.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.tui.TuiConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code kb tui} hello screen under TamboUI's Pilot (plan.md H1; {@code ToolkitTestRunner} and
 * {@code ToolkitPilot} come from the {@code test-fixtures} jar of {@code
 * dev.tamboui:tamboui-toolkit}, the {@code Pilot} interface from that of {@code tamboui-tui},
 * {@code TestBackend} from that of {@code tamboui-core}). The real terminal is KbTerminalTest's.
 */
class HelloScreenTest {

    private static final int COLUMNS = 80;
    private static final int ROWS = 24;
    private static final Duration POLL = Duration.ofMillis(10);
    private static final long WAIT_SECONDS = 5;
    private static final long POLL_MILLIS = 20;

    private ScreenBackend screen;
    private ToolkitTestRunner tui;

    @BeforeEach
    void start() throws Exception {
        screen = new ScreenBackend(COLUMNS, ROWS);
        TuiConfig config =
                TuiConfig.builder()
                        .rawMode(false)
                        .alternateScreen(false)
                        .hideCursor(false)
                        .mouseCapture(false)
                        .shutdownHook(false)
                        .pollTimeout(POLL)
                        .noTick()
                        .backend(screen)
                        .build();
        tui = ToolkitTestRunner.runTest(HelloScreen::view, new Size(COLUMNS, ROWS), config);
    }

    @AfterEach
    void stop() {
        tui.close();
    }

    @Test
    void the_hello_screen_names_agentkb() throws InterruptedException {
        await(() -> screen.text().contains("agentkb"), "agentkb not on the screen");
    }

    @Test
    void q_quits() throws InterruptedException {
        tui.pilot().press('q');

        await(() -> !tui.runner().isRunning(), "still running after q");
    }

    @Test
    void another_key_does_not_quit() {
        tui.pilot().press('x');
        tui.pilot().pause();

        assertThat(tui.runner().isRunning()).isTrue();
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

    /** TamboUI's TestBackend keeps no cells; this one keeps what was drawn, as text. */
    private static final class ScreenBackend extends TestBackend {

        private final String[][] cells;

        ScreenBackend(int columns, int rows) {
            super(columns, rows);
            cells = new String[rows][columns];
        }

        @Override
        public synchronized void draw(DiffResult diff) throws IOException {
            for (int i = 0; i < diff.size(); i++) {
                cells[diff.getY(i)][diff.getX(i)] = diff.getCell(i).symbol();
            }
            super.draw(diff);
        }

        synchronized String text() {
            StringBuilder text = new StringBuilder();
            for (String[] row : cells) {
                for (String cell : row) {
                    text.append(cell == null ? " " : cell);
                }
                text.append('\n');
            }
            return text.toString();
        }
    }
}
