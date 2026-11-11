package io.github.ismetdyn.agentkb.client.tui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.elements.TextElement;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;

/**
 * Draws a TuiState (noteapp layout, docs/CLIENTS.md §3): a status line, the list on the left and
 * the record on the right (Panels), a message line and the key hints of the focus; a form, the
 * archive question, the help and the palette as a dialog over it (Dialogs). It decides nothing.
 */
final class TuiView {

    static final String CURSOR = "▏";
    static final String GAP = "  ";
    // Status line, message line, hint line and the list panel's two borders.
    private static final int CHROME = 5;
    private static final int LIST_SHARE = 40;

    private TuiView() {}

    static void render(TuiState state, Frame frame) {
        Rect area = frame.area();
        column(
                        status(state).length(1),
                        row(
                                        Panels.list(state, area.height() - CHROME)
                                                .percent(LIST_SHARE),
                                        Panels.detail(state).fill())
                                .fill(),
                        message(state).length(1),
                        text(hints(state.mode.focus())).dim().ellipsis().length(1))
                .render(frame, area, RenderContext.empty());
        Element dialog = Dialogs.over(state, area.width());
        if (dialog != null) {
            dialog.render(frame, area, RenderContext.empty());
        }
    }

    private static TextElement status(TuiState state) {
        String query = state.listing.query();
        boolean searching = state.mode.focus() == Focus.SEARCH;
        String search =
                searching || !query.isEmpty() ? "/ " + query + (searching ? CURSOR : "") : "";
        String type = state.listing.type();
        return text("agentkb "
                        + (state.mode.isServerUp() ? "● " : "○ unreachable ")
                        + GAP
                        + "[type: "
                        + (type == null ? "all" : Keys.flat(type))
                        + "]"
                        + GAP
                        + search)
                .bold()
                .ellipsis();
    }

    private static TextElement message(TuiState state) {
        String message = state.mode.message();
        TextElement line = text(message == null ? "" : Keys.flat(message)).ellipsis();
        return state.mode.isError() ? line.red() : line.green();
    }

    /** The keys of the focus; in the list and the detail, from the command table. */
    private static String hints(Focus focus) {
        return switch (focus) {
            case LIST -> "↑↓ move  " + commandHints();
            case DETAIL -> "↑↓ scroll  Esc back  " + commandHints();
            case SEARCH -> "type to search  ↑↓ move  ↵ to the list  Esc clear/back  Ctrl+C quit";
            case FORM -> "Tab next field  ↑↓ pick from the schema  ↵ save  Esc cancel";
            case CONFIRM -> "←→ choose  ↵ confirm  Esc cancel";
            case HELP -> "Esc close";
            case PALETTE -> "type to filter  ↑↓ choose  ↵ run  Esc close";
        };
    }

    private static String commandHints() {
        StringBuilder hints = new StringBuilder();
        for (Command command : Commands.ALL) {
            if (!command.key().isEmpty()) {
                hints.append(command.key()).append(' ').append(command.id()).append(GAP);
            }
        }
        return hints.toString().strip();
    }
}
