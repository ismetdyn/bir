package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LAST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_BATCH;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.WAITING_SINCE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Add;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Conflict;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lead decisions CP3 S5 and J5 on the tombstone of ADR-0025 decision 16: a closed hostname matches
 * in any case (RFC 4343), other keys compare exactly; a close stops counting once the user opened
 * the value again after it.
 */
class WritePolicyTombstoneScopeTest {

    /** The value a user edit closed (closed_batch); its own source does not matter. */
    private static final Fact CLOSED =
            existing(AGENT, OLD_VALUE).id("closed").validTo(LAST_SEEN).build();

    private static final String CLOSED_HOST = "h1.example";
    private static final Instant REOPENED = Instant.parse("2026-04-01T09:00:00Z");
    private static final Instant REPLACED = Instant.parse("2026-04-15T09:00:00Z");

    private static Stream<Arguments> caseOfAClosedValue() {
        return Stream.of(
                arguments("hostname, other case", true, AGENT, "H1.Example", Conflict.class),
                arguments("hostname, upper case", true, SCAN, "H1.EXAMPLE", Conflict.class),
                arguments("hostname, same case", true, USER_STATED, CLOSED_HOST, Conflict.class),
                arguments("hostname, user, other case", true, USER, "H1.Example", Add.class),
                arguments("case-sensitive key, other case", false, AGENT, "H1.Example", Add.class),
                arguments(
                        "case-sensitive key, same case",
                        false,
                        AGENT,
                        CLOSED_HOST,
                        Conflict.class));
    }

    /**
     * Lead decision (CP3 S5): the tombstone compares a hostname in any case (RFC 4343); a key
     * without the hostname validator compares exactly, as every other rule of the policy does.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("caseOfAClosedValue")
    void the_tombstone_of_a_hostname_ignores_case(
            String what, boolean caseInsensitive, Source source, String value, Class<?> expected) {
        Fact closed = existing(USER, CLOSED_HOST).id("closed").validTo(LAST_SEEN).build();
        Fact incoming = incoming(source, value).batch(NEW_BATCH).build();

        Decision decision =
                WritePolicy.decide(
                        new Sourced(incoming, null),
                        List.of(new Sourced(closed, null, true)),
                        SINGLE,
                        caseInsensitive);

        assertThat(decision).as(what).isInstanceOf(expected);
    }

    /** A fact of the closed value opened by {@code source} at {@code from}, closed later. */
    private static Sourced reopened(Source source, Instant from, boolean closedByUser) {
        Fact fact =
                existing(source, OLD_VALUE)
                        .id("reopened")
                        .validFrom(from)
                        .observedAt(from)
                        .validTo(REPLACED)
                        .build();
        return new Sourced(fact, null, closedByUser);
    }

    private static Stream<Arguments> reopenedAfterTheClose() {
        return Stream.of(
                arguments(
                        "user reopened it, a user write replaced it",
                        reopened(USER, REOPENED, false),
                        Add.class),
                arguments(
                        "user reopened it, a user edit closed it again",
                        reopened(USER, REOPENED, true),
                        Conflict.class),
                arguments(
                        "a user fact of it from before the close",
                        reopened(USER, WAITING_SINCE, false),
                        Conflict.class),
                arguments(
                        "a lower source opened it after the close",
                        reopened(SCAN, REOPENED, false),
                        Conflict.class));
    }

    /**
     * Lead decision (CP3 J5): a close is a tombstone only while no later fact of the value that the
     * user opened exists. The value the user opened again and then replaced by a write of their own
     * is no longer closed by the old edit; a later close by edit is a tombstone again.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenedAfterTheClose")
    void a_close_counts_only_until_the_user_opens_the_value_again(
            String what, Sourced later, Class<?> expected) {
        List<Sourced> current = List.of(new Sourced(CLOSED, null, true), later);
        Fact incoming = incoming(SCAN, OLD_VALUE).batch(NEW_BATCH).build();

        Decision decision = WritePolicy.decide(new Sourced(incoming, null), current, SINGLE);

        assertThat(decision).as(what).isInstanceOf(expected);
    }
}
