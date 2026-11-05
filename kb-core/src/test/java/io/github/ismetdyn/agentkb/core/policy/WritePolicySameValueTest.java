package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LAST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decideSingle;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.CloseAndAdd;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC §7 rule 1 and ADR-0022 decision 22 (rewritten 2026-11-05): same key, env and value against
 * the current fact. A lower or equal source refreshes observed_at; a higher one opens a new
 * version.
 */
class WritePolicySameValueTest {

    private static Stream<Arguments> refreshTable() {
        return Stream.of(
                arguments(USER, USER),
                arguments(USER_STATED, USER),
                arguments(USER_STATED, USER_STATED),
                arguments(SCAN, USER),
                arguments(SCAN, USER_STATED),
                arguments(SCAN, SCAN),
                arguments(AGENT, USER),
                arguments(AGENT, USER_STATED),
                arguments(AGENT, SCAN),
                arguments(AGENT, AGENT));
    }

    @ParameterizedTest(name = "incoming {0}, existing {1}")
    @MethodSource("refreshTable")
    void an_equal_or_lower_source_only_refreshes_observed_at(
            Source incomingSource, Source existingSource) {
        Fact existing = existing(existingSource, OLD_VALUE).build();

        Fact refreshed =
                ((Refresh) decideSingle(incoming(incomingSource, OLD_VALUE).build(), existing))
                        .refreshed();

        assertThat(refreshed.observedAt()).isEqualTo(NOW);
        assertThat(refreshed)
                .usingRecursiveComparison()
                .ignoringFields("observedAt")
                .isEqualTo(existing);
    }

    private static Stream<Arguments> raiseTable() {
        return Stream.of(
                arguments(USER, USER_STATED),
                arguments(USER, SCAN),
                arguments(USER, AGENT),
                arguments(USER_STATED, SCAN),
                arguments(USER_STATED, AGENT),
                arguments(SCAN, AGENT));
    }

    /**
     * ADR-0022 decision 22 (2026-11-05): the fact never changes in place. The old one closes at
     * this write; the new one has the same value, the raised source, the status of that source and
     * the old valid_from (invariant 5). The caller links it with SUPERSEDES to the closed one.
     */
    @ParameterizedTest(name = "incoming {0}, existing {1}")
    @MethodSource("raiseTable")
    void a_higher_source_closes_the_fact_and_opens_a_new_one_with_the_same_value(
            Source incomingSource, Source existingSource) {
        Fact existing = existing(existingSource, OLD_VALUE).build();

        Decision decision = decideSingle(incoming(incomingSource, OLD_VALUE).build(), existing);

        assertThat(decision).isInstanceOf(CloseAndAdd.class);
        CloseAndAdd raise = (CloseAndAdd) decision;
        assertThat(raise.closed().validTo()).isEqualTo(NOW);
        assertThat(raise.closed())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(existing);
        assertThat(raise.added())
                .usingRecursiveComparison()
                .isEqualTo(
                        incoming(incomingSource, OLD_VALUE)
                                .validFrom(FIRST_SEEN)
                                .status(FactStatus.OK)
                                .build());
    }

    /** The table cannot see this: its existing facts are already OK except for the agent's. */
    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"SCAN", "AGENT"})
    void an_equal_or_lower_source_keeps_the_status_of_the_fact(Source incomingSource) {
        Fact existing = existing(SCAN, OLD_VALUE).status(FactStatus.UNVERIFIED).build();

        Fact refreshed =
                ((Refresh) decideSingle(incoming(incomingSource, OLD_VALUE).build(), existing))
                        .refreshed();

        assertThat(refreshed.source()).isEqualTo(SCAN);
        assertThat(refreshed.status()).isEqualTo(FactStatus.UNVERIFIED);
    }

    @Test
    void refresh_does_not_write_the_batch_of_the_refreshing_write() {
        Fact withoutBatch = existing(USER, OLD_VALUE).batch(null).build();
        Fact withBatch = existing(USER, OLD_VALUE).batch(OLD_BATCH).build();
        Fact incoming = incoming(USER, OLD_VALUE).batch(NEW_BATCH).build();

        assertThat(((Refresh) decideSingle(incoming, withoutBatch)).refreshed().batch()).isNull();
        assertThat(((Refresh) decideSingle(incoming, withBatch)).refreshed().batch())
                .isEqualTo(OLD_BATCH);
    }

    /**
     * `kb revert --batch` deletes what a batch opened and reopens what it closed: the new version
     * belongs to the raising batch, the closed one keeps its own.
     */
    @Test
    void raising_the_source_opens_the_new_fact_in_the_batch_of_the_raising_write() {
        Fact existing = existing(AGENT, OLD_VALUE).batch(OLD_BATCH).build();
        Fact incoming = incoming(USER, OLD_VALUE).batch(NEW_BATCH).build();

        CloseAndAdd raise = (CloseAndAdd) decideSingle(incoming, existing);

        assertThat(raise.added().batch()).isEqualTo(NEW_BATCH);
        assertThat(raise.closed().batch()).isEqualTo(OLD_BATCH);
    }

    @Test
    void a_late_arriving_older_observation_does_not_move_observed_at_back() {
        Fact existing = existing(USER, OLD_VALUE).build();
        Fact older = incoming(USER, OLD_VALUE).validFrom(LONG_AGO).observedAt(LONG_AGO).build();

        Fact refreshed = ((Refresh) decideSingle(older, existing)).refreshed();

        assertThat(refreshed.observedAt()).isEqualTo(LAST_SEEN);
        assertThat(refreshed.validFrom()).isEqualTo(FIRST_SEEN);
    }

    @Test
    void a_late_arriving_older_observation_from_higher_trust_still_raises_the_source() {
        Fact existing = existing(AGENT, OLD_VALUE).build();
        Fact older = incoming(USER, OLD_VALUE).validFrom(LONG_AGO).observedAt(LONG_AGO).build();

        CloseAndAdd raise = (CloseAndAdd) decideSingle(older, existing);

        assertThat(raise.added().source()).isEqualTo(USER);
        assertThat(raise.added().status()).isEqualTo(FactStatus.OK);
        assertThat(raise.added().observedAt()).isEqualTo(LAST_SEEN);
        assertThat(raise.added().validFrom()).isEqualTo(FIRST_SEEN);
        assertThat(raise.closed().validTo()).isAfterOrEqualTo(raise.closed().validFrom());
    }

    @Test
    void values_differing_only_by_case_are_different_values() {
        Fact existing = existing(USER, "Value").build();

        assertThat(decideSingle(incoming(USER, "value").build(), existing))
                .isInstanceOf(CloseAndAdd.class);
    }
}
