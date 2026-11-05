package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.model.Source.USER_STATED;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NEW_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.OLD_VALUE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.decide;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Add;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.CloseAndAdd;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Decision;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** No current fact for this key and env: what adds, and which facts share a key and env. */
class WritePolicyNoCurrentFactTest {

    private static Stream<Arguments> addedStatusTable() {
        return Stream.of(
                arguments(USER, FactStatus.OK),
                arguments(USER_STATED, FactStatus.OK),
                arguments(SCAN, FactStatus.OK),
                arguments(AGENT, FactStatus.UNVERIFIED));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("addedStatusTable")
    void nothing_current_adds_with_the_status_of_the_source(Source source, FactStatus status) {
        Fact incoming = incoming(source, NEW_VALUE).build();

        Add decision = (Add) decide(incoming, SINGLE);

        assertThat(decision.added().status()).isEqualTo(status);
        assertThat(decision.added())
                .usingRecursiveComparison()
                .ignoringFields("status")
                .isEqualTo(incoming);
    }

    private static Stream<Arguments> otherKeyOrEnvTable() {
        return Stream.of(
                arguments("other env", existing(USER, OLD_VALUE).env("test").build()),
                arguments("no env against an env", existing(USER, OLD_VALUE).env(null).build()),
                arguments("other key", existing(USER, OLD_VALUE).key("os").build()),
                arguments(
                        "same value in other env", existing(USER, NEW_VALUE).env("test").build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherKeyOrEnvTable")
    void a_fact_of_another_key_or_env_is_not_touched(String caseName, Fact other) {
        assertThat(decide(incoming(AGENT, NEW_VALUE).build(), SINGLE, other))
                .isInstanceOf(Add.class);
    }

    /** The seed and the scanner write "default" (ARCHITECTURE §6); a mapper may pass no env. */
    @ParameterizedTest(name = "incoming env [{0}]")
    @NullSource
    @ValueSource(strings = {"", "  ", Fact.DEFAULT_ENV})
    void a_missing_or_blank_env_is_the_default_env(String incomingEnv) {
        Fact existing = existing(USER, OLD_VALUE).env(Fact.DEFAULT_ENV).build();

        Decision decision =
                decide(incoming(USER, NEW_VALUE).env(incomingEnv).build(), SINGLE, existing);

        assertThat(decision).isInstanceOf(CloseAndAdd.class);
        assertThat(((CloseAndAdd) decision).added().env()).isEqualTo(Fact.DEFAULT_ENV);
    }

    @Test
    void the_value_is_carried_whole_because_limits_belong_to_the_write_boundary() {
        String longValue = " " + "ş".repeat(5_000) + " ";

        Add decision = (Add) decide(incoming(USER, longValue).build(), SINGLE);

        assertThat(decision.added().value()).isEqualTo(longValue);
    }
}
