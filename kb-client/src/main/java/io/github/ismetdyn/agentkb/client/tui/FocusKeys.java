package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;

/**
 * What a key does in each focus (noteapp's rule): the list and the detail run the command table and
 * move; the search, a form and the palette take letters as text; the archive question starts on
 * Cancel; Esc steps back one level.
 */
final class FocusKeys {

    private FocusKeys() {}

    static List<Intent> browse(TuiState s, KeyEvent key) {
        Command command = Commands.find(Keys.token(key));
        if (command != null) {
            return run(s, command);
        }
        int step = Keys.step(key);
        if (step == 0) {
            return back(s, key);
        }
        if (s.mode.focus() == Focus.DETAIL) {
            s.detail.scrollBy(step);
            return List.of();
        }
        return s.listing.move(step, s.detail);
    }

    /** Esc (or ← in the detail) steps back: from the detail to the list, then clears the search. */
    private static List<Intent> back(TuiState s, KeyEvent key) {
        boolean escape = key.isKey(KeyCode.ESCAPE);
        if (s.mode.focus() == Focus.DETAIL && (escape || key.isLeft())) {
            s.mode.go(Focus.LIST);
        } else if (escape && !s.listing.query().isEmpty()) {
            return List.of(s.listing.setQuery(""));
        }
        return List.of();
    }

    /** Typing searches; Enter keeps the result and goes to the list; Esc clears, then leaves. */
    static List<Intent> search(TuiState s, KeyEvent key) {
        String query = s.listing.query();
        if (key.isKey(KeyCode.ENTER)) {
            s.mode.go(Focus.LIST);
            return List.of();
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            return clear(s);
        }
        if (key.isUp() || key.isDown()) {
            return s.listing.move(key.isUp() ? -1 : 1, s.detail);
        }
        String typed = key.isKey(KeyCode.BACKSPACE) ? Keys.dropLast(query) : query + Keys.text(key);
        return typed.equals(query) ? List.of() : List.of(s.listing.setQuery(typed));
    }

    /** Esc in the search: the query goes first, at once; an empty search goes to the list. */
    private static List<Intent> clear(TuiState s) {
        if (s.listing.query().isEmpty()) {
            s.mode.go(Focus.LIST);
            return List.of();
        }
        s.listing.setQuery("");
        return List.of(s.listing.load(false));
    }

    static List<Intent> form(TuiState s, KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            s.forms.close();
            s.mode.close();
            return List.of();
        }
        Intent submit = s.forms.form().onKey(key);
        return submit == null ? List.of() : List.of(submit);
    }

    /**
     * ← or n choose Cancel, → or y Archive (Tab changes nothing: it is too easy to hit); only Enter
     * on "Archive" archives, and it archives the record the question was opened for, whatever the
     * list shows now (CWE-367).
     */
    static List<Intent> confirm(TuiState s, KeyEvent key) {
        Forms.Question question = s.forms.question();
        if (key.isLeft() || key.isRight() || key.isChar('y') || key.isChar('n')) {
            s.forms.choose(key.isRight() || key.isChar('y'));
            return List.of();
        }
        if (!key.isKey(KeyCode.ESCAPE) && !key.isKey(KeyCode.ENTER)) {
            return List.of();
        }
        s.forms.close();
        s.mode.close();
        boolean archive = question.archive() && key.isKey(KeyCode.ENTER);
        return archive ? List.of(new Intent.Archive(question.id())) : List.of();
    }

    static List<Intent> help(TuiState s, KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isChar('?') || key.isChar('q')) {
            s.mode.close();
        }
        return List.of();
    }

    /** A blocked row says why and the palette stays; a runnable one closes it and runs. */
    static List<Intent> palette(TuiState s, KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            s.mode.close();
            return List.of();
        }
        Command chosen = s.palette.onKey(key);
        if (chosen == null || blocked(s, chosen)) {
            return List.of();
        }
        s.mode.close();
        return chosen.run().apply(s);
    }

    private static List<Intent> run(TuiState s, Command command) {
        return blocked(s, command) ? List.of() : command.run().apply(s);
    }

    private static boolean blocked(TuiState s, Command command) {
        String reason = command.blocked().apply(s);
        if (reason != null) {
            s.mode.say(command.id() + ": " + reason, true);
        }
        return reason != null;
    }
}
