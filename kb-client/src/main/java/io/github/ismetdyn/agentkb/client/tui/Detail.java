package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.api.EntityAnswer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The record on the right: which one was asked for last, its answer once it came and how far it is
 * scrolled. An answer for another record (the selection moved on) is dropped.
 */
final class Detail {

    static final String NO_RECORD = "no record selected";

    private @Nullable String wanted;
    private @Nullable EntityAnswer entity;
    private int scroll;

    @Nullable EntityAnswer entity() {
        return entity;
    }

    int scroll() {
        return scroll;
    }

    boolean isWanted(@Nullable String id) {
        return id != null && id.equals(wanted);
    }

    boolean isArchived() {
        return entity != null && EntityLines.ARCHIVED.equals(entity.status());
    }

    /** Asks for {@code id}; the old record is not shown meanwhile. */
    List<Intent> want(String id) {
        wanted = id;
        entity = null;
        scroll = 0;
        return List.of(new Intent.Open(id));
    }

    void loaded(String id, EntityAnswer answer) {
        if (isWanted(id)) {
            entity = answer;
        }
    }

    /** After a write: the next list answer asks for the record again. */
    void forget() {
        wanted = null;
    }

    void scrollBy(int lines) {
        int last = entity == null ? 0 : EntityLines.of(entity).size() - 1;
        scroll = Math.clamp((long) scroll + lines, 0, Math.max(0, last));
    }

    /** Why a command that needs the record cannot run, null if it can. */
    @Nullable String blocked(@Nullable String selected) {
        if (selected == null) {
            return NO_RECORD;
        }
        return entity == null ? "the record is still loading" : null;
    }
}
