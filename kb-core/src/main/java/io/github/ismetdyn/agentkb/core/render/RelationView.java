package io.github.ismetdyn.agentkb.core.render;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A vocabulary relation seen from one entity: {@code outgoing} if that entity is its start, {@code
 * other} the entity at the far end. An ended relation has a {@code validTo}. {@code id} is the
 * edge's own, which {@code POST /relations/{id}/close} takes; null where the reader did not ask for
 * it (a path step).
 */
public record RelationView(
        String type,
        boolean outgoing,
        String other,
        @Nullable String env,
        Source source,
        Instant validFrom,
        Instant observedAt,
        @Nullable Instant validTo,
        @Nullable String id) {

    /** A relation without its edge id. */
    public RelationView(
            String type,
            boolean outgoing,
            String other,
            @Nullable String env,
            Source source,
            Instant validFrom,
            Instant observedAt,
            @Nullable Instant validTo) {
        this(type, outgoing, other, env, source, validFrom, observedAt, validTo, null);
    }
}
