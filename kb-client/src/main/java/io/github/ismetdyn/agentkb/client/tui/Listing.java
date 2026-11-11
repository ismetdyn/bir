package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The list on the left: what it was asked for (query and type), the rows of the latest answer and
 * the selection. Every request is numbered; only the answer of the latest is shown (typing makes
 * many). An empty query browses ({@code GET /entities}), any other searches.
 */
final class Listing {

    /** A row from a search hit or a browsed entity; {@code faded} if not current or archived. */
    record Row(String id, String type, String mark, @Nullable String note, boolean faded) {

        static Row of(EntityListAnswer.Entity entity) {
            return new Row(
                    entity.id(),
                    entity.type(),
                    entity.status(),
                    entity.summary(),
                    EntityLines.ARCHIVED.equals(entity.status()));
        }

        static Row of(SearchAnswer.Hit hit) {
            return new Row(
                    hit.id(),
                    hit.type(),
                    hit.archived() ? hit.tier() + ", archived" : hit.tier(),
                    hit.snippet(),
                    hit.archived() || !"current".equals(hit.tier()));
        }
    }

    /** What the view draws: the rows, how many more the server left out and the selection. */
    record Shown(List<Row> rows, int more, int selected) {}

    private String query = "";
    private @Nullable String type;
    private long seq;
    private Shown shown = new Shown(List.of(), 0, 0);

    String query() {
        return query;
    }

    @Nullable String type() {
        return type;
    }

    Shown shown() {
        return shown;
    }

    @Nullable String selectedId() {
        return shown.rows().isEmpty() ? null : shown.rows().get(shown.selected()).id();
    }

    boolean isLatest(long answer) {
        return answer == seq;
    }

    /** The next request for the list as it stands. */
    Intent.LoadList load(boolean debounced) {
        seq++;
        return new Intent.LoadList(seq, query, type, debounced);
    }

    /** A new query, sent once the typing pauses. */
    Intent.LoadList setQuery(String text) {
        query = text;
        return load(true);
    }

    /** All types, then each type of the schema in turn, then all again. */
    Intent.LoadList nextType(SchemaAnswer schema) {
        List<String> keys =
                schema.entityTypes().stream().map(SchemaAnswer.EntityType::key).toList();
        int at = type == null ? -1 : keys.indexOf(type);
        type = at + 1 < keys.size() ? keys.get(at + 1) : null;
        return load(false);
    }

    /**
     * The rows of answer {@code answer} if it is the latest; the selection stays on its record if
     * that is still there, and the detail asks for it.
     */
    List<Intent> show(long answer, List<Row> rows, int more, Detail detail) {
        if (!isLatest(answer)) {
            return List.of();
        }
        String keep = selectedId();
        int selected = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(keep)) {
                selected = i;
            }
        }
        shown = new Shown(List.copyOf(rows), more, selected);
        return move(0, detail);
    }

    /** Moves the selection; the detail follows it. */
    List<Intent> move(int step, Detail detail) {
        if (shown.rows().isEmpty()) {
            return List.of();
        }
        int selected = Math.clamp((long) shown.selected() + step, 0, shown.rows().size() - 1);
        shown = new Shown(shown.rows(), shown.more(), selected);
        String id = selectedId();
        return detail.isWanted(id) ? List.of() : detail.want(id);
    }
}
