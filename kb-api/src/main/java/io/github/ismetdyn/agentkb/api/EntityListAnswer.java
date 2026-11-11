package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/entities?type=&limit=}: every entity without a search, by id, archived ones
 * last and marked by their status (invariant 12); {@code more} counts those past the limit.
 */
public record EntityListAnswer(List<Entity> entities, int more) {

    public EntityListAnswer {
        entities = List.copyOf(entities);
    }

    /**
     * {@code type} is the graph label; {@code summary} one line cut as a search snippet (300
     * characters and an ellipsis); {@code updatedAt} the last write (ISO-8601), empty if an
     * imported record has none.
     */
    public record Entity(
            String id, String type, String status, @Nullable String summary, String updatedAt) {}
}
