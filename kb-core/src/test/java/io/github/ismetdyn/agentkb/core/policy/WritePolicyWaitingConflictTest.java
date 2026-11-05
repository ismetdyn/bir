package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.DISPUTED_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.EXISTING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.WAITING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.WAITING_SINCE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decide;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.waiting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.CloseAndAdd;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Resolve;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC §7 rule 1 and ADR-0022 decision 23 (rewritten 2026-11-05): writing a value that waits with
 * status conflict. Resolving closes facts and opens a new one; nothing changes in place.
 */
class WritePolicyWaitingConflictTest {

    private static Fact write(Source source) {
        return incoming(source, DISPUTED_VALUE).batch(NEW_BATCH).build();
    }

    private static Stream<Arguments> promotionTable() {
        return Stream.of(
                arguments(USER, USER, AGENT, USER),
                arguments(USER, USER, USER_STATED, USER),
                arguments(USER, USER_STATED, SCAN, USER),
                arguments(USER_STATED, USER_STATED, AGENT, USER_STATED),
                arguments(USER_STATED, SCAN, AGENT, USER_STATED),
                arguments(SCAN, SCAN, AGENT, SCAN),
                // Only an import can leave this state; invariant 6 still forbids lowering.
                arguments(AGENT, AGENT, SCAN, SCAN));
    }

    /**
     * ADR-0022 decision 23 (2026-11-05): both the waiting and the old current fact close at this
     * write, and one new current fact opens; the caller links it with SUPERSEDES to both. The
     * ranges end at this write and not at the waiting valid_from, which may lie before it. The new
     * fact has the same value, this write's id and batch, and the waiting valid_from.
     */
    @ParameterizedTest(name = "incoming {0}, current {1}, waiting {2}: source {3}")
    @MethodSource("promotionTable")
    void trust_at_or_above_the_current_value_closes_both_and_opens_one_current_fact(
            Source incomingSource,
            Source currentSource,
            Source waitingSource,
            Source expectedSource) {
        Fact current = existing(currentSource, OLD_VALUE).build();
        Fact waiting = waiting(waitingSource);

        Decision decision = decide(write(incomingSource), SINGLE, waiting, current);

        assertThat(decision).isInstanceOf(Resolve.class);
        Resolve resolve = (Resolve) decision;
        assertThat(resolve.closedWaiting().validTo()).isEqualTo(NOW);
        assertThat(resolve.closedWaiting())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(waiting);
        assertThat(resolve.closedCurrent().validTo()).isEqualTo(NOW);
        assertThat(resolve.closedCurrent())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(current);
        assertThat(resolve.added())
                .usingRecursiveComparison()
                .isEqualTo(
                        incoming(expectedSource, DISPUTED_VALUE)
                                .validFrom(WAITING_SINCE)
                                .status(FactStatus.OK)
                                .batch(NEW_BATCH)
                                .build());
    }

    @Test
    void the_order_of_the_facts_does_not_change_which_one_closes_as_which() {
        Fact current = existing(USER, OLD_VALUE).build();
        Fact waiting = waiting(AGENT);

        Decision currentFirst = decide(write(USER), SINGLE, current, waiting);

        assertThat(currentFirst).isEqualTo(decide(write(USER), SINGLE, waiting, current));
        assertThat(currentFirst).isInstanceOf(Resolve.class);
        assertThat(((Resolve) currentFirst).closedCurrent().id()).isEqualTo(EXISTING_ID);
        assertThat(((Resolve) currentFirst).closedWaiting().id()).isEqualTo(WAITING_ID);
    }

    @Test
    void a_resolving_write_with_an_older_valid_from_ends_no_fact_before_it_began() {
        Fact current = existing(USER, OLD_VALUE).build();
        Fact older =
                incoming(USER, DISPUTED_VALUE).validFrom(LONG_AGO).observedAt(LONG_AGO).build();

        Resolve resolve = (Resolve) decide(older, SINGLE, waiting(AGENT), current);

        assertThat(resolve.closedWaiting().validTo()).isAfterOrEqualTo(WAITING_SINCE);
        assertThat(resolve.closedCurrent().validTo()).isAfterOrEqualTo(FIRST_SEEN);
        assertThat(resolve.added().validFrom()).isEqualTo(WAITING_SINCE);
    }

    private static Stream<Arguments> belowTheCurrentValueTable() {
        return Stream.of(
                arguments(USER_STATED, USER),
                // Above the waiting fact's own source but below the current value: no rise either.
                arguments(SCAN, USER),
                arguments(AGENT, USER),
                arguments(SCAN, USER_STATED),
                arguments(AGENT, USER_STATED),
                arguments(AGENT, SCAN));
    }

    @ParameterizedTest(name = "incoming {0}, current {1}")
    @MethodSource("belowTheCurrentValueTable")
    void trust_below_the_current_value_only_refreshes_and_the_fact_stays_in_conflict(
            Source incomingSource, Source currentSource) {
        Fact waiting = waiting(AGENT);

        Fact current = existing(currentSource, OLD_VALUE).build();

        Decision decision = decide(write(incomingSource), SINGLE, current, waiting);

        Fact refreshed = ((Refresh) decision).refreshed();
        assertThat(refreshed.observedAt()).isEqualTo(NOW);
        assertThat(refreshed)
                .usingRecursiveComparison()
                .ignoringFields("observedAt")
                .isEqualTo(waiting);
    }

    @Test
    void the_same_disputed_value_again_refreshes_the_conflict_fact_instead_of_adding_another() {
        Fact current = existing(USER, OLD_VALUE).build();
        Fact waiting =
                existing(AGENT, "disputed").id("waiting").status(FactStatus.CONFLICT).build();

        Refresh decision =
                (Refresh) decide(incoming(AGENT, "disputed").build(), SINGLE, current, waiting);

        assertThat(decision.refreshed().id()).isEqualTo("waiting");
        assertThat(decision.refreshed().status()).isEqualTo(FactStatus.CONFLICT);
        assertThat(decision.refreshed().observedAt()).isEqualTo(NOW);
    }

    /**
     * Nothing is left to dispute (the current fact was reverted or closed by the user). The waiting
     * fact still does not change in place: it closes and a new current fact opens.
     */
    @Test
    void with_no_current_value_only_the_waiting_fact_closes_and_a_new_current_fact_opens() {
        Fact waiting = waiting(AGENT);

        CloseAndAdd decision = (CloseAndAdd) decide(write(SCAN), SINGLE, waiting);

        assertThat(decision.closed())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(waiting);
        assertThat(decision.closed().validTo()).isEqualTo(NOW);
        assertThat(decision.added())
                .usingRecursiveComparison()
                .isEqualTo(
                        incoming(SCAN, DISPUTED_VALUE)
                                .validFrom(WAITING_SINCE)
                                .status(FactStatus.OK)
                                .batch(NEW_BATCH)
                                .build());
    }

    /** SPEC §7 rule 5: writing a value on a multi-valued key closes no other value. */
    @Test
    void a_multi_valued_key_resolves_a_waiting_value_without_closing_another() {
        Fact other = existing(USER, OLD_VALUE).build();

        Decision decision = decide(write(USER), MULTI, waiting(AGENT), other);

        CloseAndAdd resolve = (CloseAndAdd) decision;
        assertThat(resolve.closed().id()).isEqualTo(WAITING_ID);
        assertThat(resolve.closed().validTo()).isEqualTo(NOW);
        assertThat(resolve.added())
                .usingRecursiveComparison()
                .isEqualTo(
                        incoming(USER, DISPUTED_VALUE)
                                .validFrom(WAITING_SINCE)
                                .status(FactStatus.OK)
                                .batch(NEW_BATCH)
                                .build());
    }
}
