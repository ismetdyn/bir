package io.github.ismetdyn.agentkb.core.render;

import io.github.ismetdyn.agentkb.core.model.Fact;
import org.jspecify.annotations.Nullable;

/**
 * A fact with what the graph links to it: {@code target} is the entity id of its TARGET edge (a
 * port's project), {@code origin} where it came from, already formatted ({@code
 * <repo>:<path>:<line>} or a document id), {@code episode} the session or event it came from,
 * already formatted (ADR-0023).
 */
public record FactView(
        Fact fact, @Nullable String target, @Nullable String origin, @Nullable String episode) {

    /** A fact of no known episode. */
    public FactView(Fact fact, @Nullable String target, @Nullable String origin) {
        this(fact, target, origin, null);
    }
}
