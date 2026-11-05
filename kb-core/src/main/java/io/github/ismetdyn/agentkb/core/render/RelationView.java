package io.github.ismetdyn.agentkb.core.render;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A vocabulary relation seen from one entity: {@code outgoing} if that entity is its start, {@code
 * other} the entity at the far end. An ended relation has a {@code validTo}.
 */
public record RelationView(
        String type,
        boolean outgoing,
        String other,
        @Nullable String env,
        Source source,
        Instant validFrom,
        Instant observedAt,
        @Nullable Instant validTo) {}
