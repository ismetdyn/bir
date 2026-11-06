package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.DISPUTED_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LAST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.WAITING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.WAITING_SINCE;
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
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0025 decision 16 (tombstone): a value a user edit closed stays closed. A lower source than
 * the user (user_stated, scan, agent) writing it again does not make it current: it waits as a
 * conflict for the user, whatever the key's multi flag or the slot's current value. Only the user
 * opens it again, as a new fact (decisions 22-23 for its valid_from).
 */
class WritePolicyUserClosedTest {

    private static final List<Source> BELOW_USER = List.of(USER_STATED, SCAN, AGENT);

    /** The value a user edit closed (closed_batch); its own source does not matter. */
    private static final Fact CLOSED =
            existing(AGENT, OLD_VALUE).id("closed").validTo(LAST_SEEN).build();

    /** {@code source} writes {@code value}; {@code closed} is passed as closed by a user edit. */
    private static Decision decide(
            Source source, String value, boolean multi, Fact closed, Fact... current) {
        List<Sourced> sourced =
                Stream.concat(
                                Stream.of(new Sourced(closed, null, true)),
                                Stream.of(current).map(fact -> new Sourced(fact, null)))
                        .toList();
        Fact incoming = incoming(source, value).batch(NEW_BATCH).build();
        return WritePolicy.decide(new Sourced(incoming, null), sourced, multi);
    }

    private static Stream<Arguments> lowerSourceAndSlot() {
        return BELOW_USER.stream()
                .flatMap(
                        source ->
                                Stream.of(
                                        arguments(source, SINGLE, null),
                                        arguments(source, MULTI, null),
                                        // Without the tombstone a trust >= scan would replace it.
                                        arguments(source, SINGLE, SCAN),
                                        arguments(source, MULTI, AGENT)));
    }

    @ParameterizedTest(name = "{0}, multi {1}, current {2}")
    @MethodSource("lowerSourceAndSlot")
    void a_lower_source_writing_a_value_the_user_closed_waits_as_a_conflict(
            Source source, boolean multi, Source currentSource) {
        Fact[] current =
                currentSource == null
                        ? new Fact[0]
                        : new Fact[] {existing(currentSource, NEW_VALUE).build()};

        Decision decision = decide(source, OLD_VALUE, multi, CLOSED, current);

        assertThat(decision).isInstanceOf(Conflict.class);
        Fact added = ((Conflict) decision).added();
        assertThat(added.value()).isEqualTo(OLD_VALUE);
        assertThat(added.status()).isEqualTo(FactStatus.CONFLICT);
        assertThat(added.source()).isEqualTo(source);
        assertThat(added.validTo()).isNull();
    }

    /** The waiting value seen again only refreshes; with no holder it would otherwise win. */
    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_lower_source_writing_it_again_refreshes_the_waiting_value(Source source) {
        Fact waiting =
                existing(AGENT, OLD_VALUE)
                        .id(WAITING_ID)
                        .status(FactStatus.CONFLICT)
                        .validFrom(WAITING_SINCE)
                        .build();

        Decision decision = decide(source, OLD_VALUE, SINGLE, CLOSED, waiting);

        assertThat(decision).isInstanceOf(Refresh.class);
        assertThat(((Refresh) decision).refreshed().id()).isEqualTo(WAITING_ID);
        assertThat(((Refresh) decision).refreshed().observedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest(name = "multi {0}")
    @ValueSource(booleans = {SINGLE, MULTI})
    void the_user_writing_the_closed_value_opens_it_as_a_new_current_fact(boolean multi) {
        Decision decision = decide(USER, OLD_VALUE, multi, CLOSED);

        assertThat(decision).isInstanceOf(Add.class);
        Fact added = ((Add) decision).added();
        assertThat(added.value()).isEqualTo(OLD_VALUE);
        assertThat(added.source()).isEqualTo(USER);
        assertThat(added.status()).isEqualTo(FactStatus.OK);
        assertThat(added.validFrom()).as("a new period: the write's time").isEqualTo(NOW);
    }

    /** ADR-0022 decision 23: the waiting value's valid_from is carried, not the write's. */
    @ParameterizedTest(name = "multi {0}")
    @ValueSource(booleans = {SINGLE, MULTI})
    void the_user_writing_a_waiting_closed_value_resolves_it_with_the_waiting_valid_from(
            boolean multi) {
        Fact waiting =
                existing(SCAN, OLD_VALUE)
                        .id(WAITING_ID)
                        .status(FactStatus.CONFLICT)
                        .validFrom(WAITING_SINCE)
                        .build();

        Decision decision = decide(USER, OLD_VALUE, multi, CLOSED, waiting);

        assertThat(decision).isInstanceOf(CloseAndAdd.class);
        CloseAndAdd change = (CloseAndAdd) decision;
        assertThat(change.closed().id()).isEqualTo(WAITING_ID);
        assertThat(change.added().source()).isEqualTo(USER);
        assertThat(change.added().status()).isEqualTo(FactStatus.OK);
        assertThat(change.added().validFrom()).isEqualTo(WAITING_SINCE);
    }

    /** Once the user opened it again it is current: a lower source only confirms it. */
    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_closed_value_the_user_opened_again_is_confirmed_by_a_lower_source(Source source) {
        Fact reopened = existing(USER, OLD_VALUE).id("reopened").build();

        Decision decision = decide(source, OLD_VALUE, SINGLE, CLOSED, reopened);

        assertThat(decision).isInstanceOf(Refresh.class);
        assertThat(((Refresh) decision).refreshed().id()).isEqualTo("reopened");
    }

    private static Stream<Arguments> unrelatedClosedFacts() {
        return Stream.of(
                arguments("another value", CLOSED, NEW_VALUE),
                arguments(
                        "another env",
                        existing(USER, DISPUTED_VALUE)
                                .id("closed")
                                .env("test")
                                .validTo(LAST_SEEN)
                                .build(),
                        DISPUTED_VALUE),
                arguments(
                        "another key",
                        existing(USER, DISPUTED_VALUE)
                                .id("closed")
                                .key("os")
                                .validTo(LAST_SEEN)
                                .build(),
                        DISPUTED_VALUE));
    }

    /** The tombstone holds one value in one key and env, nothing next to it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unrelatedClosedFacts")
    void a_closed_value_of_another_slot_or_value_changes_nothing(
            String what, Fact closed, String value) {
        Decision decision = decide(AGENT, value, SINGLE, closed);

        assertThat(decision).as(what).isInstanceOf(Add.class);
        assertThat(((Add) decision).added().status()).isEqualTo(FactStatus.UNVERIFIED);
    }
}
