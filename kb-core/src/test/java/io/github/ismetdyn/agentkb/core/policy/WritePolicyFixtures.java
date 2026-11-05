package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Shared values and builders of the WritePolicy tests. Expected cells stay in the tests. */
final class WritePolicyFixtures {

    static final Instant FIRST_SEEN = Instant.parse("2026-01-15T09:00:00Z");
    static final Instant LAST_SEEN = Instant.parse("2026-03-01T09:00:00Z");
    static final Instant NOW = Instant.parse("2026-05-10T09:00:00Z");
    static final Instant LONG_AGO = Instant.parse("2025-06-01T09:00:00Z");
    // Differs from the current fact's valid_from, so a test sees which one the policy kept.
    static final Instant WAITING_SINCE = Instant.parse("2026-02-01T09:00:00Z");

    static final String EXISTING_ID = "existing";
    static final String INCOMING_ID = "incoming";
    static final String WAITING_ID = "waiting";
    static final String OLD_VALUE = "old-value";
    static final String NEW_VALUE = "new-value";
    static final String DISPUTED_VALUE = "disputed";
    static final String OLD_BATCH = "batch-old";
    static final String NEW_BATCH = "batch-new";
    static final String FILE_A = "group/app:application.yml";
    static final String FILE_B = "group/app:.env";
    static final boolean SINGLE = false;
    static final boolean MULTI = true;

    static final List<Source> RANKED = List.of(USER, USER_STATED, SCAN, AGENT);

    private static final String KEY = "hostname";
    private static final String ENV = "prod";
    private static final String EVIDENCE = "user said so";

    private WritePolicyFixtures() {}

    static Decision decideSingle(Fact incoming, Fact existing) {
        return decide(incoming, SINGLE, existing);
    }

    static Decision decide(Fact incoming, boolean multi, Fact... current) {
        List<Sourced> sourced = Stream.of(current).map(fact -> new Sourced(fact, null)).toList();
        return WritePolicy.decide(new Sourced(incoming, null), sourced, multi);
    }

    static FactBuilder existing(Source source, String value) {
        return new FactBuilder(EXISTING_ID, source, value, FIRST_SEEN, LAST_SEEN);
    }

    /** A value that waits with status conflict next to the current fact (SPEC §7 rule 3). */
    static Fact waiting(Source source) {
        return existing(source, DISPUTED_VALUE)
                .id(WAITING_ID)
                .status(FactStatus.CONFLICT)
                .validFrom(WAITING_SINCE)
                .batch(OLD_BATCH)
                .build();
    }

    /** Always claims OK, so a test can only see UNVERIFIED or CONFLICT if the policy decided it. */
    static FactBuilder incoming(Source source, String value) {
        return new FactBuilder(INCOMING_ID, source, value, NOW, NOW).status(FactStatus.OK);
    }

    /** The only place that knows the shape of the Fact record. */
    static final class FactBuilder {

        private String id;
        private String key = KEY;
        private final String value;
        private String env = ENV;
        private final Source source;
        private Instant validFrom;
        private Instant observedAt;
        private Instant validTo;
        private FactStatus status;
        private String batch;

        private FactBuilder(
                String id, Source source, String value, Instant validFrom, Instant observedAt) {
            this.id = id;
            this.source = source;
            this.value = value;
            this.validFrom = validFrom;
            this.observedAt = observedAt;
            this.status = source == AGENT ? FactStatus.UNVERIFIED : FactStatus.OK;
        }

        FactBuilder id(String newId) {
            this.id = newId;
            return this;
        }

        FactBuilder key(String newKey) {
            this.key = newKey;
            return this;
        }

        FactBuilder env(String newEnv) {
            this.env = newEnv;
            return this;
        }

        FactBuilder validFrom(Instant newValidFrom) {
            this.validFrom = newValidFrom;
            return this;
        }

        FactBuilder observedAt(Instant newObservedAt) {
            this.observedAt = newObservedAt;
            return this;
        }

        FactBuilder validTo(Instant newValidTo) {
            this.validTo = newValidTo;
            return this;
        }

        FactBuilder status(FactStatus newStatus) {
            this.status = newStatus;
            return this;
        }

        FactBuilder batch(String newBatch) {
            this.batch = newBatch;
            return this;
        }

        Fact build() {
            return new Fact(
                    id,
                    key,
                    value,
                    env,
                    source,
                    validFrom,
                    observedAt,
                    validTo,
                    status,
                    EVIDENCE,
                    batch);
        }
    }
}
