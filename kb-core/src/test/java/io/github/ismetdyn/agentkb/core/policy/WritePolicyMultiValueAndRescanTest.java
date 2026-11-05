package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.EXISTING_ID;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_A;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_B;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.RANKED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC §7 rules 4 and 5, seed/schema/facts.yaml `multi`: rescans and multi-valued keys. */
class WritePolicyMultiValueAndRescanTest {

    @Test
    void a_rescan_of_the_same_file_closes_its_old_value() {
        Sourced existing = new Sourced(existing(SCAN, OLD_VALUE).build(), FILE_A);

        Decision decision =
                WritePolicy.decide(
                        new Sourced(incoming(SCAN, NEW_VALUE).build(), FILE_A),
                        List.of(existing),
                        SINGLE);

        assertThat(((CloseAndAdd) decision).closed().id()).isEqualTo(EXISTING_ID);
    }

    /**
     * Invariant 6 is absolute: rule 4 says "same scan source", which a value the user wrote is not.
     */
    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER", "USER_STATED"})
    void a_rescan_of_the_same_file_cannot_close_a_higher_trust_value_tied_to_that_file(
            Source existingSource) {
        Sourced existing = new Sourced(existing(existingSource, OLD_VALUE).build(), FILE_A);

        Decision decision =
                WritePolicy.decide(
                        new Sourced(incoming(SCAN, NEW_VALUE).build(), FILE_A),
                        List.of(existing),
                        SINGLE);

        assertThat(decision).isInstanceOf(Conflict.class);
    }

    private static Stream<Arguments> everySourcePair() {
        return RANKED.stream()
                .flatMap(
                        incoming -> RANKED.stream().map(existing -> arguments(incoming, existing)));
    }

    /** The user too adds: closing a value of a multi-valued key takes an explicit user close. */
    @ParameterizedTest(name = "incoming {0}, existing {1}")
    @MethodSource("everySourcePair")
    void a_multi_valued_key_adds_a_different_value_and_closes_nothing(
            Source incomingSource, Source existingSource) {
        Sourced existing = new Sourced(existing(existingSource, OLD_VALUE).build(), FILE_A);

        Sourced incoming = new Sourced(incoming(incomingSource, NEW_VALUE).build(), FILE_B);

        Decision decision = WritePolicy.decide(incoming, List.of(existing), MULTI);

        assertThat(decision).isInstanceOf(Add.class);
    }

    /**
     * One file lists two ports. One fact at a time cannot tell a sibling of the same scan from a
     * value that left the file, so decide() adds; closedByRescan closes after the whole file.
     */
    @Test
    void two_values_of_one_file_on_a_multi_valued_key_both_stay_open() {
        Sourced firstPort = new Sourced(existing(SCAN, "8080").id("port-8080").build(), FILE_A);

        Decision second =
                WritePolicy.decide(
                        new Sourced(incoming(SCAN, "9090").build(), FILE_A),
                        List.of(firstPort),
                        MULTI);

        assertThat(second).isInstanceOf(Add.class);
    }

    private static Stream<Arguments> notARescanTable() {
        return Stream.of(
                arguments("agent naming the file", AGENT, FILE_A, SCAN, FILE_A),
                arguments("user_stated naming the file", USER_STATED, FILE_A, SCAN, FILE_A),
                arguments("user naming the file", USER, FILE_A, SCAN, FILE_A),
                arguments("over a user value tied to the file", SCAN, FILE_A, USER, FILE_A),
                arguments("two scans with no known file", SCAN, null, SCAN, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notARescanTable")
    void what_is_not_a_rescan_of_the_same_file_does_not_close_on_a_multi_valued_key(
            String caseName,
            Source incomingSource,
            String incomingFile,
            Source existingSource,
            String existingFile) {
        Sourced existing = new Sourced(existing(existingSource, OLD_VALUE).build(), existingFile);

        Sourced incoming = new Sourced(incoming(incomingSource, NEW_VALUE).build(), incomingFile);

        Decision decision = WritePolicy.decide(incoming, List.of(existing), MULTI);

        assertThat(decision).isInstanceOf(Add.class);
    }

    @Test
    void a_multi_valued_key_refreshes_the_matching_value_among_several() {
        Sourced first = new Sourced(existing(SCAN, "192.0.2.20").id("first").build(), FILE_A);
        Sourced second = new Sourced(existing(USER, "192.0.2.21").id("second").build(), FILE_B);

        Sourced incoming = new Sourced(incoming(AGENT, "192.0.2.21").build(), null);

        Decision decision = WritePolicy.decide(incoming, List.of(first, second), MULTI);

        Fact refreshed = ((Refresh) decision).refreshed();
        assertThat(refreshed.id()).isEqualTo("second");
        assertThat(refreshed.source()).isEqualTo(USER);
        assertThat(refreshed.validFrom()).isEqualTo(FIRST_SEEN);
    }

    @Test
    void a_multi_valued_key_raises_the_source_of_the_matching_value_only() {
        Sourced first = new Sourced(existing(AGENT, "192.0.2.20").id("first").build(), FILE_A);
        Sourced second = new Sourced(existing(AGENT, "192.0.2.21").id("second").build(), FILE_B);

        Sourced incoming = new Sourced(incoming(USER, "192.0.2.21").build(), null);

        Decision decision = WritePolicy.decide(incoming, List.of(first, second), MULTI);

        CloseAndAdd raise = (CloseAndAdd) decision;
        assertThat(raise.closed().id()).isEqualTo("second");
        assertThat(raise.closed().source()).isEqualTo(AGENT);
        assertThat(raise.added().value()).isEqualTo("192.0.2.21");
        assertThat(raise.added().source()).isEqualTo(USER);
        assertThat(raise.added().status()).isEqualTo(FactStatus.OK);
        assertThat(raise.added().validFrom()).isEqualTo(FIRST_SEEN);
    }
}
