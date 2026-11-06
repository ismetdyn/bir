package io.github.ismetdyn.agentkb.core.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * SPEC §6.3. An open fact has no {@code validTo}. A missing or blank {@code env} becomes {@link
 * #DEFAULT_ENV}, so "no env" has one form in the graph and in the policy (ARCHITECTURE §6).
 */
public record Fact(
        String id,
        String key,
        String value,
        String env,
        Source source,
        Instant validFrom,
        Instant observedAt,
        @Nullable Instant validTo,
        FactStatus status,
        @Nullable String evidence,
        @Nullable String batch) {

    public static final String DEFAULT_ENV = "default";

    public Fact {
        // A mapper may still hand over no env; the record is where it gets its one form.
        if (env == null || env.isBlank()) {
            env = DEFAULT_ENV;
        }
    }

    public Fact withObservedAt(Instant newObservedAt) {
        return new Fact(
                id,
                key,
                value,
                env,
                source,
                validFrom,
                newObservedAt,
                validTo,
                status,
                evidence,
                batch);
    }

    /** Seen again at {@code at}: observed_at moves forward only, never back (SPEC §6.3). */
    public Fact seenAt(Instant at) {
        return at.isAfter(observedAt) ? withObservedAt(at) : this;
    }

    public Fact withValidTo(Instant newValidTo) {
        return new Fact(
                id,
                key,
                value,
                env,
                source,
                validFrom,
                observedAt,
                newValidTo,
                status,
                evidence,
                batch);
    }

    public Fact withStatus(FactStatus newStatus) {
        return new Fact(
                id,
                key,
                value,
                env,
                source,
                validFrom,
                observedAt,
                validTo,
                newStatus,
                evidence,
                batch);
    }
}
