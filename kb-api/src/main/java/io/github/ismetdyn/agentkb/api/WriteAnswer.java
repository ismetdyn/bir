package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code POST /api/v1/entities}, {@code …/{id}/facts} and {@code …/{id}/relations}: the entity
 * written, whether it is new, the one batch {@code kb revert --batch} takes, and the ids of the
 * facts added, refreshed and closed. {@code conflicts} are the records that wait next to a value
 * the user closed or trusts more (fact or relation ids).
 */
public record WriteAnswer(
        String entity,
        boolean created,
        String batch,
        List<String> added,
        List<String> refreshed,
        List<String> closed,
        List<String> conflicts) {

    public WriteAnswer {
        added = List.copyOf(added);
        refreshed = List.copyOf(refreshed);
        closed = List.copyOf(closed);
        conflicts = List.copyOf(conflicts);
    }
}
