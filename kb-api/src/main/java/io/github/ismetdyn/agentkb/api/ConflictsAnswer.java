package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code GET /api/v1/conflicts}: the values waiting as a conflict, oldest first, and how many more
 * there were past the limit. {@code id} is what {@code POST /conflicts/{id}/resolve} takes.
 */
public record ConflictsAnswer(List<Conflict> conflicts, int more) {

    public ConflictsAnswer {
        conflicts = List.copyOf(conflicts);
    }

    /** The waiting value next to the current values of the same key and env ({@code current}). */
    public record Conflict(
            String id,
            String entity,
            String key,
            String env,
            String value,
            String source,
            String validFrom,
            List<String> current) {

        public Conflict {
            current = List.copyOf(current);
        }
    }
}
