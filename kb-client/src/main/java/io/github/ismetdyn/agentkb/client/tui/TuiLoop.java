package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import io.github.ismetdyn.agentkb.client.KbApi;
import io.github.ismetdyn.agentkb.client.KbException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs {@code kb tui}: keys go to TuiState, its Intents become REST calls on one background thread
 * so the screen never waits for the server, and each answer goes back to TuiState on the render
 * thread, which then redraws. Typing in the search waits {@link #DEBOUNCE} for a pause; a newer
 * query or record makes the older one skip its request, and TuiState drops an answer it did not ask
 * for last. Each call waits at most KbApi.INTERACTIVE.
 */
final class TuiLoop implements EventHandler, Renderer, AutoCloseable {

    static final Duration DEBOUNCE = Duration.ofMillis(150);

    private final TuiState state = new TuiState();
    private final KbApi api;
    private final ExecutorService calls =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("kb-tui-rest").factory());
    private final AtomicLong latestList = new AtomicLong();
    private final AtomicReference<String> latestOpen = new AtomicReference<>();
    private TuiRunner runner;

    TuiLoop(KbApi api) {
        this.api = api;
    }

    /** Asks for the schema and the list; call it once, before or while {@code runner} runs. */
    void start(TuiRunner tui) {
        runner = tui;
        tui.runOnRenderThread(() -> apply(state::start));
    }

    @Override
    public boolean handle(Event event, TuiRunner tui) {
        return switch (event) {
            case KeyEvent key -> {
                perform(state.onKey(key));
                yield true;
            }
            case PasteEvent paste -> {
                perform(state.onPaste(paste.text()));
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public void render(Frame frame) {
        TuiView.render(state, frame);
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }

    private void perform(List<Intent> intents) {
        for (Intent intent : intents) {
            switch (intent) {
                case Intent.Quit quit -> runner.quit();
                case Intent.Open open -> {
                    latestOpen.set(open.id());
                    call(open);
                }
                case Intent.LoadList load -> {
                    latestList.set(load.seq());
                    if (load.debounced()) {
                        runner.scheduler()
                                .schedule(
                                        () -> call(load),
                                        DEBOUNCE.toMillis(),
                                        TimeUnit.MILLISECONDS);
                    } else {
                        call(load);
                    }
                }
                default -> call(intent);
            }
        }
    }

    /**
     * One REST call off the render thread; a list query that is no longer the latest is skipped.
     */
    private void call(Intent intent) {
        calls.execute(
                () -> {
                    if (isStale(intent)) {
                        return;
                    }
                    Supplier<List<Intent>> answer;
                    try {
                        answer = request(intent);
                    } catch (KbException e) {
                        answer = failure(intent, e.getMessage(), e.isUnreachable());
                    } catch (RuntimeException e) {
                        // As kb's own handler: the class, never a JDK message that may quote a
                        // value; on this thread it would otherwise be printed over the screen.
                        answer =
                                failure(
                                        intent,
                                        "unexpected failure (" + e.getClass().getName() + ")",
                                        false);
                    }
                    Supplier<List<Intent>> told = answer;
                    runner.runOnRenderThread(() -> apply(told));
                });
    }

    /**
     * A list that is not the latest asked for, or a record the selection has left: TuiState would
     * drop its answer, so it is not asked for.
     */
    private boolean isStale(Intent intent) {
        return switch (intent) {
            case Intent.LoadList load -> load.seq() != latestList.get();
            case Intent.Open open -> !open.id().equals(latestOpen.get());
            default -> false;
        };
    }

    /** The server's answer to {@code intent}, as what to tell TuiState on the render thread. */
    private Supplier<List<Intent>> request(Intent intent) {
        return switch (intent) {
            case Intent.LoadSchema schema -> {
                var answer = api.schema();
                yield () -> {
                    state.schemaLoaded(answer);
                    return List.of();
                };
            }
            case Intent.LoadList load when load.query().isBlank() -> {
                var answer = api.entities(load.type(), TuiState.BROWSE_LIMIT);
                yield () -> state.listLoaded(load.seq(), answer);
            }
            case Intent.LoadList load -> {
                var answer = api.search(load.query(), load.type(), null, TuiState.SEARCH_LIMIT);
                yield () -> state.listLoaded(load.seq(), answer);
            }
            case Intent.Open open -> {
                var answer = api.entity(open.id());
                yield () -> {
                    state.entityLoaded(open.id(), answer);
                    return List.of();
                };
            }
            case Intent.Edit edit -> {
                EditAnswer answer = api.edit(edit.id(), edit.aliases(), edit.summary());
                yield () -> state.written(intent, answer.batch(), 0);
            }
            case Intent.SetFact fact -> {
                WriteAnswer answer = api.setFact(fact.id(), fact.key(), fact.value(), fact.env());
                yield () -> state.written(intent, answer.batch(), answer.conflicts().size());
            }
            case Intent.AddRelation relation -> {
                WriteAnswer answer =
                        api.addRelation(relation.id(), relation.type(), relation.target());
                yield () -> state.written(intent, answer.batch(), answer.conflicts().size());
            }
            case Intent.Archive archive -> {
                EditAnswer answer = api.archive(archive.id());
                yield () -> state.written(intent, answer.batch(), 0);
            }
            case Intent.Unarchive unarchive -> {
                EditAnswer answer = api.unarchive(unarchive.id());
                yield () -> state.written(intent, answer.batch(), 0);
            }
            case Intent.Quit quit -> List::of;
        };
    }

    private Supplier<List<Intent>> failure(Intent intent, String message, boolean unreachable) {
        return () -> {
            state.failed(intent, message, unreachable);
            return List.of();
        };
    }

    /** On the render thread: tell TuiState, carry out what it asks, redraw. */
    private void apply(Supplier<List<Intent>> change) {
        perform(change.get());
        if (runner.isRunning()) {
            runner.draw(this::render);
        }
    }
}
