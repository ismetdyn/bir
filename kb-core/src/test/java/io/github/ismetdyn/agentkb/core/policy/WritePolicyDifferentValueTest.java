package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.EXISTING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.INCOMING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LAST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decide;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decideSingle;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Add;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.CloseAndAdd;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Conflict;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC §7 rules 2 and 3: a different value on a single-valued key. */
class WritePolicyDifferentValueTest {

    private static Stream<Arguments> closeOrConflictTable() {
        return Stream.of(
                arguments(USER, USER, CloseAndAdd.class),
                arguments(USER, USER_STATED, CloseAndAdd.class),
                arguments(USER, SCAN, CloseAndAdd.class),
                arguments(USER, AGENT, CloseAndAdd.class),
                arguments(USER_STATED, USER, Conflict.class),
                arguments(USER_STATED, USER_STATED, CloseAndAdd.class),
                arguments(USER_STATED, SCAN, CloseAndAdd.class),
                arguments(USER_STATED, AGENT, CloseAndAdd.class),
                arguments(SCAN, USER, Conflict.class),
                arguments(SCAN, USER_STATED, Conflict.class),
                arguments(SCAN, SCAN, CloseAndAdd.class),
                arguments(SCAN, AGENT, CloseAndAdd.class),
                arguments(AGENT, USER, Conflict.class),
                arguments(AGENT, USER_STATED, Conflict.class),
                arguments(AGENT, SCAN, Conflict.class),
                arguments(AGENT, AGENT, CloseAndAdd.class));
    }

    @ParameterizedTest(name = "incoming {0}, existing {1}: {2}")
    @MethodSource("closeOrConflictTable")
    void different_value_closes_or_conflicts_by_source_order(
            Source incomingSource, Source existingSource, Class<? extends Decision> expected) {
        Decision decision =
                decideSingle(
                        incoming(incomingSource, NEW_VALUE).build(),
                        existing(existingSource, OLD_VALUE).build());

        assertThat(decision).isInstanceOf(expected);
    }

    @Test
    void close_and_add_keeps_the_old_range_and_starts_the_new_fact_where_the_old_one_ended() {
        Fact existing = existing(USER, OLD_VALUE).batch(OLD_BATCH).build();
        Fact incoming = incoming(USER, NEW_VALUE).batch(NEW_BATCH).build();

        CloseAndAdd decision = (CloseAndAdd) decideSingle(incoming, existing);

        assertThat(decision.closed().validTo()).isEqualTo(NOW);
        assertThat(decision.closed())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(existing);
        assertThat(decision.added().validFrom()).isEqualTo(decision.closed().validTo());
        assertThat(decision.added().validTo()).isNull();
        assertThat(decision.added()).usingRecursiveComparison().isEqualTo(incoming);
    }

    /** G3 open item: a write that first saw its value before the old fact began. */
    @Test
    void a_new_value_with_an_older_valid_from_never_ends_the_old_fact_before_it_began() {
        Fact existing = existing(SCAN, OLD_VALUE).build();
        Fact older = incoming(USER, NEW_VALUE).validFrom(LONG_AGO).observedAt(LONG_AGO).build();

        CloseAndAdd decision = (CloseAndAdd) decideSingle(older, existing);

        assertThat(decision.closed().validTo()).isAfterOrEqualTo(FIRST_SEEN);
        assertThat(decision.closed())
                .usingRecursiveComparison()
                .ignoringFields("validTo")
                .isEqualTo(existing);
    }

    @Test
    void conflict_leaves_the_current_fact_current_and_writes_the_new_value_marked() {
        Fact existing = existing(USER, OLD_VALUE).build();
        Fact incoming = incoming(AGENT, NEW_VALUE).batch(NEW_BATCH).build();

        Conflict decision = (Conflict) decideSingle(incoming, existing);

        assertThat(decision.current()).isEqualTo(existing);
        assertThat(decision.current().validTo()).isNull();
        assertThat(decision.added().status()).isEqualTo(FactStatus.CONFLICT);
        assertThat(decision.added())
                .usingRecursiveComparison()
                .ignoringFields("status")
                .isEqualTo(incoming);
    }

    /**
     * The current fact decides, whatever a closed older one once was; a refresh never reopens a
     * closed fact.
     */
    @Test
    void closed_facts_take_no_part_in_the_decision() {
        Fact closedHighTrust = existing(USER, NEW_VALUE).id("closed").validTo(LAST_SEEN).build();
        Fact currentLowTrust = existing(AGENT, OLD_VALUE).build();

        CloseAndAdd overCurrent =
                (CloseAndAdd)
                        decide(
                                incoming(AGENT, "third-value").build(),
                                SINGLE,
                                closedHighTrust,
                                currentLowTrust);
        Add afterHistoryOnly =
                (Add) decide(incoming(AGENT, NEW_VALUE).build(), SINGLE, closedHighTrust);
        CloseAndAdd valueComesBack =
                (CloseAndAdd)
                        decide(
                                incoming(AGENT, NEW_VALUE).build(),
                                SINGLE,
                                closedHighTrust,
                                currentLowTrust);

        assertThat(overCurrent.closed().id()).isEqualTo(EXISTING_ID);
        assertThat(afterHistoryOnly.added().id()).isEqualTo(INCOMING_ID);
        assertThat(valueComesBack.closed().id()).isEqualTo(EXISTING_ID);
        assertThat(valueComesBack.added().id()).isEqualTo(INCOMING_ID);
    }

    /**
     * A conflict fact has no valid_to but is not the current value: SPEC §7 rule 3 keeps the
     * existing one current.
     */
    @Test
    void trust_is_compared_with_the_ok_fact_and_not_with_a_waiting_conflict_fact() {
        Fact current = existing(USER, OLD_VALUE).build();
        Fact waiting =
                existing(AGENT, "disputed").id("waiting").status(FactStatus.CONFLICT).build();

        Conflict lowTrust =
                (Conflict) decide(incoming(AGENT, NEW_VALUE).build(), SINGLE, waiting, current);
        CloseAndAdd highTrust =
                (CloseAndAdd) decide(incoming(USER, NEW_VALUE).build(), SINGLE, waiting, current);

        assertThat(lowTrust.current().id()).isEqualTo(EXISTING_ID);
        assertThat(highTrust.closed().id()).isEqualTo(EXISTING_ID);
    }

    @Test
    void an_unverified_source_closing_its_own_kind_stays_unverified() {
        Fact existing = existing(AGENT, OLD_VALUE).status(FactStatus.UNVERIFIED).build();

        CloseAndAdd decision =
                (CloseAndAdd) decideSingle(incoming(AGENT, NEW_VALUE).build(), existing);

        assertThat(decision.added().status()).isEqualTo(FactStatus.UNVERIFIED);
    }
}
