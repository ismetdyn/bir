package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.tui.event.KeyEvent;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import java.util.List;

/**
 * The whole state of {@code kb tui} (tui-gui-references §1.4): keys and server answers in, Intents
 * out; it never calls the server and never draws, so it is tested without either (TuiStateTest).
 * TuiLoop carries the Intents out, TuiView draws the parts. Each part keeps its own state; the
 * fields are final so the view and the key handling reach the same parts.
 *
 * <p>Nothing is checked here that the server checks: a form sends what was typed and shows the
 * server's refusal as it came.
 */
final class TuiState {

    static final int SEARCH_LIMIT = 20;
    static final int BROWSE_LIMIT = 500;

    final Mode mode = new Mode();
    final Listing listing = new Listing();
    final Detail detail = new Detail();
    final Forms forms = new Forms();
    final Palette palette = new Palette();

    /** The first requests: the schema and every entity. */
    List<Intent> start() {
        return List.of(new Intent.LoadSchema(), listing.load(false));
    }

    /** Ctrl+C quits from anywhere; any other key is the focus's (FocusKeys). */
    List<Intent> onKey(KeyEvent key) {
        if (key.isCtrlC()) {
            return List.of(new Intent.Quit());
        }
        return switch (mode.focus()) {
            case LIST, DETAIL -> FocusKeys.browse(this, key);
            case SEARCH -> FocusKeys.search(this, key);
            case FORM -> FocusKeys.form(this, key);
            case CONFIRM -> FocusKeys.confirm(this, key);
            case HELP -> FocusKeys.help(this, key);
            case PALETTE -> FocusKeys.palette(this, key);
        };
    }

    /** Pasted text goes into the field being typed, on one line; elsewhere it is ignored. */
    List<Intent> onPaste(String text) {
        String line = Keys.printable(text.replaceAll("\\R", " "));
        switch (mode.focus()) {
            case SEARCH -> {
                return List.of(listing.setQuery(listing.query() + line));
            }
            case FORM -> forms.form().paste(line);
            case PALETTE -> palette.type(line);
            default -> {
                // Nothing to type into.
            }
        }
        return List.of();
    }

    void schemaLoaded(SchemaAnswer answer) {
        mode.reach(true);
        forms.learn(answer);
    }

    List<Intent> listLoaded(long seq, EntityListAnswer answer) {
        mode.reach(true);
        return listing.show(
                seq,
                answer.entities().stream().map(Listing.Row::of).toList(),
                answer.more(),
                detail);
    }

    List<Intent> listLoaded(long seq, SearchAnswer answer) {
        mode.reach(true);
        return listing.show(
                seq, answer.hits().stream().map(Listing.Row::of).toList(), answer.more(), detail);
    }

    void entityLoaded(String id, EntityAnswer answer) {
        mode.reach(true);
        detail.loaded(id, answer);
    }

    /** A write was saved: the form closes, the batch is named, the list and detail reload. */
    List<Intent> written(Intent cause, String batch, int conflicts) {
        mode.reach(true);
        if (cause instanceof Intent.FormWrite write && forms.isOpen(write.form())) {
            forms.close();
            mode.close();
        }
        mode.say(
                done(cause)
                        + "; batch "
                        + batch
                        + " (kb revert --batch "
                        + batch
                        + " undoes it)"
                        + (conflicts > 0 ? "; " + conflicts + " waiting as a conflict" : ""),
                false);
        detail.forget();
        return List.of(listing.load(false));
    }

    /**
     * {@code failure} is a KbException's message: the server's error and hint, or why it cannot be
     * reached. The form that sent a write shows it; an answer nobody waits for is dropped.
     */
    void failed(Intent cause, String failure, boolean unreachable) {
        mode.reach(!unreachable);
        switch (cause) {
            case Intent.LoadList load when !listing.isLatest(load.seq()) -> {
                // A newer list was asked for.
            }
            case Intent.Open open when !detail.isWanted(open.id()) -> {
                // The selection moved on.
            }
            case Intent.FormWrite write when forms.isOpen(write.form()) ->
                    forms.form().refuse(failure);
            default -> mode.say(failure, true);
        }
    }

    private static String done(Intent cause) {
        return switch (cause) {
            case Intent.Edit e -> "edited " + e.id();
            case Intent.SetFact f -> "fact " + f.key() + " set on " + f.id();
            case Intent.AddRelation r -> "relation added to " + r.id();
            case Intent.Archive a -> "archived " + a.id();
            case Intent.Unarchive u -> "unarchived " + u.id();
            default -> "saved";
        };
    }
}
