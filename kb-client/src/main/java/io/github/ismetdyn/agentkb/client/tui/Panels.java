package io.github.ismetdyn.agentkb.client.tui;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.TextElement;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.ArrayList;
import java.util.List;

/**
 * The two panels: the list (current rows ●, faded ones ○ and dim, the selection reversed, how many
 * more the server left out) and the record (EntityLines). The panel that has the keys, or that a
 * dialog returns to, has a cyan border.
 */
final class Panels {

    private Panels() {}

    static Panel list(TuiState state, int height) {
        Listing.Shown shown = state.listing.shown();
        int visible = Math.max(1, height);
        int first = Math.max(0, shown.selected() - visible + 1);
        List<Element> lines = new ArrayList<>();
        for (int i = first; i < shown.rows().size() && i < first + visible; i++) {
            lines.add(row(shown.rows().get(i), i == shown.selected()));
        }
        if (shown.rows().isEmpty()) {
            lines.add(text(state.listing.query().isEmpty() ? "no records" : "no matches").dim());
        }
        if (shown.more() > 0) {
            lines.add(text(shown.more() + " more not shown").dim().length(1));
        }
        return panel(
                        state.listing.query().isEmpty() ? "Records" : "Search",
                        lines.toArray(Element[]::new))
                .rounded()
                .borderColor(border(state, Focus.LIST, Focus.SEARCH));
    }

    static Panel detail(TuiState state) {
        EntityAnswer entity = state.detail.entity();
        List<Element> lines = new ArrayList<>();
        if (entity == null) {
            lines.add(text(state.listing.selectedId() == null ? "" : "loading…").dim());
        } else {
            List<EntityLines.Line> all = EntityLines.of(entity);
            for (EntityLines.Line line :
                    all.subList(Math.min(state.detail.scroll(), all.size()), all.size())) {
                TextElement drawn = text(line.text()).ellipsis().length(1);
                if (line.faded()) {
                    drawn.dim();
                }
                lines.add(line.alert() ? drawn.red() : drawn);
            }
        }
        return panel(
                        entity == null ? "Detail" : Keys.flat(entity.id()),
                        lines.toArray(Element[]::new))
                .rounded()
                .borderColor(border(state, Focus.DETAIL));
    }

    private static TextElement row(Listing.Row row, boolean selected) {
        TextElement line =
                text(Keys.flat(
                                (row.faded() ? "○ " : "● ")
                                        + row.id()
                                        + TuiView.GAP
                                        + row.type()
                                        + TuiView.GAP
                                        + row.mark()
                                        + (row.note() == null ? "" : TuiView.GAP + row.note())))
                        .ellipsis()
                        .length(1);
        if (row.faded()) {
            line.dim();
        }
        return selected ? line.reversed() : line;
    }

    private static Color border(TuiState state, Focus... panel) {
        Focus shown = state.mode.isOverlay() ? state.mode.back() : state.mode.focus();
        for (Focus focus : panel) {
            if (focus == shown) {
                return Color.CYAN;
            }
        }
        return Color.GRAY;
    }
}
