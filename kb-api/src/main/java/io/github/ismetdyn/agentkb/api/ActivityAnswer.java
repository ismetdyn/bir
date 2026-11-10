package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code GET /api/v1/activity}: the latest batches, newest first, and how many more matched past
 * the limit. Each is one {@code kb revert --batch}.
 */
public record ActivityAnswer(List<Batch> batches, int more) {

    public ActivityAnswer {
        batches = List.copyOf(batches);
    }

    /**
     * {@code at} is the last time the batch wrote (ISO-8601); {@code sources} the sources of what
     * it opened, user for a user's close or edit; the records it {@code opened}, {@code closed} and
     * {@code revised}; up to ten {@code entities} it touched.
     */
    public record Batch(
            String batch,
            String at,
            List<String> sources,
            int opened,
            int closed,
            int revised,
            List<String> entities) {

        public Batch {
            sources = List.copyOf(sources);
            entities = List.copyOf(entities);
        }
    }
}
