package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.contract;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import io.github.ismetdyn.agentkb.client.ApiStub;
import io.github.ismetdyn.agentkb.client.KbApi;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * {@code kb tui} under TamboUI's Pilot (plan.md H1, H8a; {@code TuiTestRunner} and the {@code
 * Pilot} interface come from the {@code test-fixtures} jar of {@code dev.tamboui:tamboui-tui},
 * {@code TestBackend} from that of {@code tamboui-core}): the real TuiLoop, TuiState and TuiView
 * against a loopback stub that answers with the contract files of fixtures/api/. The real terminal
 * is KbTerminalScenarios'.
 */
final class PilotHarness implements AutoCloseable {

    static final String APP = "/api/v1/entities/ornek-app-01";

    private static final int COLUMNS = 160;
    private static final int ROWS = 30;
    private static final Duration POLL = Duration.ofMillis(10);
    private static final long WAIT_SECONDS = 10;
    private static final long POLL_MILLIS = 20;

    private final ScreenBackend screen;
    private final TuiTestRunner tui;
    private final TuiLoop loop;
    private final ApiStub server;

    private PilotHarness(KbApi api, ApiStub server) throws Exception {
        this.server = server;
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
        loop = new TuiLoop(api);
        tui = TuiTestRunner.runTest(loop, loop, new Size(COLUMNS, ROWS), config);
        loop.start(tui.runner());
    }

    /**
     * A stub with the schema, the entity list, a search, {@code entityJson} for ornek-app-01, a 422
     * refusal of its facts and an archive that succeeds.
     */
    static ApiStub stub(String entityJson) throws IOException {
        return new ApiStub()
                .answer("GET", "/api/v1/schema", 200, contract("schema-answer.json"))
                .answer("GET", "/api/v1/entities", 200, contract("entity-list-answer.json"))
                .answer("GET", APP, 200, entityJson)
                .answer("GET", "/api/v1/search", 200, contract("search-answer.json"))
                .answer(
                        "POST",
                        APP + "/facts",
                        422,
                        "{\"error\": \"invalid value for engine\","
                                + " \"hint\": \"one of: oracle, postgresql\"}")
                .answer("POST", APP + "/archive", 200, contract("edit-answer.json"));
    }

    /** The TUI against {@code server}, with the short timeout kb tui uses. */
    static PilotHarness on(ApiStub server) throws Exception {
        return new PilotHarness(new KbApi(server.url(), KbApi.INTERACTIVE), server);
    }

    /** The TUI against {@code base}, with an idle stub nobody calls. */
    static PilotHarness against(String base) throws Exception {
        return new PilotHarness(new KbApi(base, KbApi.INTERACTIVE), new ApiStub());
    }

    Pilot pilot() {
        return tui.pilot();
    }

    ApiStub server() {
        return server;
    }

    String text() {
        return screen.text();
    }

    boolean isRunning() {
        return tui.runner().isRunning();
    }

    void awaitScreen(String text) throws InterruptedException {
        await(() -> screen.text().contains(text), "not on the screen: " + text);
    }

    void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(failure + "\n" + screen.text());
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    @Override
    public void close() {
        tui.close();
        loop.close();
        server.close();
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
