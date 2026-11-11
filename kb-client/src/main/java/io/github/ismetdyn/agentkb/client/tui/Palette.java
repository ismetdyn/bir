package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code :} palette: every command of the table by id or label, filtered as you type; an exact
 * id first, then ids that start with the text, then the rest in table order.
 */
final class Palette {

    private String query = "";
    private int at;

    String query() {
        return query;
    }

    /** The chosen row, kept inside the rows. */
    int at() {
        return Math.min(at, Math.max(0, rows().size() - 1));
    }

    List<Command> rows() {
        return Commands.ALL.stream()
                .filter(c -> Keys.contains(c.id(), query) || Keys.contains(c.label(), query))
                .sorted(Comparator.comparingInt(this::rank))
                .toList();
    }

    void reset() {
        query = "";
        at = 0;
    }

    void type(String text) {
        query = query + text;
        at = 0;
    }

    /** The command Enter chose, else null; ↑↓ choose, Backspace and text filter. */
    @Nullable Command onKey(KeyEvent key) {
        List<Command> rows = rows();
        if (key.isKey(KeyCode.ENTER)) {
            return rows.isEmpty() ? null : rows.get(at());
        }
        if (key.isUp() || key.isDown()) {
            at = rows.isEmpty() ? 0 : Math.floorMod(at() + (key.isUp() ? -1 : 1), rows.size());
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            query = Keys.dropLast(query);
            at = 0;
        } else if (!Keys.text(key).isEmpty()) {
            type(Keys.text(key));
        }
        return null;
    }

    private int rank(Command command) {
        if (command.id().equalsIgnoreCase(query)) {
            return 0;
        }
        return command.id().regionMatches(true, 0, query, 0, query.length()) ? 1 : 2;
    }
}
