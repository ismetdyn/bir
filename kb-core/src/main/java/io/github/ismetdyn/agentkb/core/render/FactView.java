package io.github.ismetdyn.agentkb.core.render;

import io.github.ismetdyn.agentkb.core.model.Fact;
import org.jspecify.annotations.Nullable;

/**
 * A fact with what the graph links to it: {@code target} is the entity id of its TARGET edge (a
 * port's project), {@code origin} where it came from, already formatted ({@code
 * <repo>:<path>:<line>} or a document id).
 */
public record FactView(Fact fact, @Nullable String target, @Nullable String origin) {}
