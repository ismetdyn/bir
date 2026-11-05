package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.CURATOR;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.DISPUTED_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_A;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_B;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.RANKED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decide;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.waiting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Add;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.CloseAndAdd;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Conflict;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Resolve;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rules over the whole policy: CLAUDE.md invariants 5 and 6 and the curator. The rules one by one
 * are in the other WritePolicy*Test classes (SPEC §6.3 and §7, ADR-0006, ADR-0022 decisions 10, 22
 * and 23). Expected cells are written out.
 */
class WritePolicyTest {

    // SPEC §7 table, written out so the invariant check below does not reuse what the
    // implementation computes.
    private static final Map<Source, Integer> TRUST =
            Map.of(USER, 4, USER_STATED, 3, SCAN, 2, AGENT, 1);

    private static Stream<Arguments> everyCell() {
        return RANKED.stream()
                .flatMap(
                        incoming ->
                                RANKED.stream()
                                        .flatMap(existing -> cellsOfPair(incoming, existing)));
    }

    private static Stream<Arguments> cellsOfPair(Source incoming, Source existing) {
        return Stream.of(SINGLE, MULTI)
                .flatMap(
                        multi ->
                                Stream.of(OLD_VALUE, NEW_VALUE)
                                        .flatMap(
                                                value ->
                                                        Stream.of(FILE_A, FILE_B, null)
                                                                .map(
                                                                        file ->
                                                                                arguments(
                                                                                        incoming,
                                                                                        existing,
                                                                                        multi,
                                                                                        value,
                                                                                        file))));
    }

    /**
     * The switch has no default on purpose: it stops compiling if Decision is not sealed over
     * exactly these five (ADR-0022 decisions 22-23: no Promote, a raise is a CloseAndAdd).
     */
    @ParameterizedTest(name = "incoming {0}, existing {1}, multi {2}, value {3}, file {4}")
    @MethodSource("everyCell")
    void a_lower_trust_source_never_changes_a_higher_trust_fact(
            Source incomingSource,
            Source existingSource,
            boolean multi,
            String value,
            String incomingFile) {
        Fact existing = existing(existingSource, OLD_VALUE).build();
        Sourced incoming = new Sourced(incoming(incomingSource, value).build(), incomingFile);

        Decision decision =
                WritePolicy.decide(incoming, List.of(new Sourced(existing, FILE_A)), multi);

        Fact existingAfter =
                switch (decision) {
                    case Refresh refresh -> refresh.refreshed();
                    case Add add -> existing;
                    case CloseAndAdd closeAndAdd -> closeAndAdd.closed();
                    case Conflict conflict -> conflict.current();
                    case Resolve resolve -> resolve.closedCurrent();
                };
        assertThat(existingAfter.validFrom()).isEqualTo(FIRST_SEEN);
        assertThat(TRUST.get(existingAfter.source()))
                .isGreaterThanOrEqualTo(TRUST.get(existingSource));
        if (!value.equals(OLD_VALUE)) {
            assertThat(existingAfter.source()).isEqualTo(existingSource);
        }
        if (TRUST.get(incomingSource) < TRUST.get(existingSource)) {
            assertThat(existingAfter.source()).isEqualTo(existingSource);
            assertThat(existingAfter.value()).isEqualTo(OLD_VALUE);
            assertThat(existingAfter.validTo()).isNull();
        }
    }

    /**
     * SPEC §7 rule 1 and ADR-0022 decisions 22-23 (2026-11-05): a fact never changes in place. Over
     * every source pair, with and without a current and a waiting conflict fact, every value, file
     * and an older incoming valid_from: an existing fact either only gets a later observed_at or is
     * closed, never before its valid_from; anything else is a new fact with its own id.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyState")
    void no_decision_changes_an_existing_fact_other_than_refreshing_or_closing_it(State state) {
        List<Fact> before = new ArrayList<>();
        if (state.current() != null) {
            before.add(existing(state.current(), OLD_VALUE).build());
        }
        if (state.waiting() != null) {
            before.add(waiting(state.waiting()));
        }
        Fact incoming =
                incoming(state.incoming(), state.value())
                        .validFrom(state.validFrom())
                        .observedAt(state.validFrom())
                        .build();

        Decision decision =
                WritePolicy.decide(
                        new Sourced(incoming, state.file()),
                        before.stream().map(fact -> new Sourced(fact, FILE_A)).toList(),
                        state.multi());

        assertOnlyRefreshedOrClosed(decision, before);
    }

    /** One cell of the property above; null current or waiting means there is none. */
    record State(
            Source incoming,
            @Nullable Source current,
            @Nullable Source waiting,
            boolean multi,
            String value,
            @Nullable String file,
            Instant validFrom) {}

    private static Stream<State> everyState() {
        List<Source> sourceOrNone = new ArrayList<>(RANKED);
        sourceOrNone.add(null);
        List<State> states = new ArrayList<>();
        for (Source incoming : RANKED) {
            for (Source current : sourceOrNone) {
                for (Source waiting : sourceOrNone) {
                    states.addAll(statesOf(incoming, current, waiting));
                }
            }
        }
        return states.stream();
    }

    private static List<State> statesOf(
            Source incoming, @Nullable Source current, @Nullable Source waiting) {
        List<State> states = new ArrayList<>();
        for (boolean multi : List.of(SINGLE, MULTI)) {
            for (String value : List.of(OLD_VALUE, NEW_VALUE, DISPUTED_VALUE)) {
                for (String file : Arrays.asList(FILE_A, null)) {
                    for (Instant validFrom : List.of(NOW, LONG_AGO)) {
                        states.add(
                                new State(
                                        incoming, current, waiting, multi, value, file, validFrom));
                    }
                }
            }
        }
        return states;
    }

    private static void assertOnlyRefreshedOrClosed(Decision decision, List<Fact> before) {
        Map<String, Fact> beforeById =
                before.stream().collect(Collectors.toMap(Fact::id, fact -> fact));
        List<Fact> touched =
                switch (decision) {
                    case Refresh refresh -> List.of(refresh.refreshed());
                    case Add add -> List.of();
                    case CloseAndAdd closeAndAdd -> List.of(closeAndAdd.closed());
                    case Conflict conflict -> List.of(conflict.current());
                    case Resolve resolve ->
                            List.of(resolve.closedWaiting(), resolve.closedCurrent());
                };
        Optional<Fact> added =
                switch (decision) {
                    case Refresh refresh -> Optional.empty();
                    case Add add -> Optional.of(add.added());
                    case CloseAndAdd closeAndAdd -> Optional.of(closeAndAdd.added());
                    case Conflict conflict -> Optional.of(conflict.added());
                    case Resolve resolve -> Optional.of(resolve.added());
                };
        for (Fact after : touched) {
            Fact original = beforeById.get(after.id());
            assertThat(original).as("%s is an existing fact", after.id()).isNotNull();
            assertThat(after)
                    .as("only observed_at or valid_to of %s change", after.id())
                    .usingRecursiveComparison()
                    .ignoringFields("observedAt", "validTo")
                    .isEqualTo(original);
            if (after.validTo() == null) {
                assertThat(after.observedAt())
                        .as("refreshing %s never moves observed_at back", after.id())
                        .isAfterOrEqualTo(original.observedAt());
            } else {
                assertThat(after.observedAt())
                        .as("closing %s does not refresh it", after.id())
                        .isEqualTo(original.observedAt());
                assertThat(after.validTo())
                        .as("%s closes no earlier than it began", after.id())
                        .isAfterOrEqualTo(after.validFrom());
            }
        }
        added.ifPresent(
                fact -> {
                    assertThat(beforeById)
                            .as("a new version has its own id")
                            .doesNotContainKey(fact.id());
                    assertThat(fact.validTo()).as("a new fact is open").isNull();
                });
    }

    // SPEC §7: the curator has no trust level and produces no new information.
    @Test
    void the_curator_cannot_write_a_fact_through_the_policy() {
        Fact incoming = incoming(CURATOR, NEW_VALUE).build();
        Fact sameValue = incoming(CURATOR, OLD_VALUE).build();
        Fact existing = existing(AGENT, OLD_VALUE).build();

        assertThatThrownBy(() -> decide(incoming, SINGLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decide(incoming, SINGLE, existing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decide(sameValue, SINGLE, existing))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
