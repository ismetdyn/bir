package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_A;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_B;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FIRST_SEEN;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.SINGLE;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Conflict;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Refresh;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC §7 rules 4 and 5 after a whole file was scanned: which same-file values close. */
class WritePolicyClosedByRescanTest {

    private static final String PORT = "port";

    @Test
    void a_rescan_of_one_file_with_two_ports_keeps_both_on_every_scan() {
        Fact port8080 = existing(SCAN, "8080").id("p8080").key(PORT).build();
        Fact port9090 = existing(SCAN, "9090").id("p9090").key(PORT).build();
        List<Sourced> current = List.of(sourced(port8080), sourced(port9090));

        // Second scan: both values arrive again, one at a time, then the file is done.
        assertThat(WritePolicy.decide(scanOf("8080"), current, MULTI)).isInstanceOf(Refresh.class);
        assertThat(WritePolicy.decide(scanOf("9090"), current, MULTI)).isInstanceOf(Refresh.class);
        assertThat(
                        WritePolicy.closedByRescan(
                                FILE_A,
                                List.of(port("8080"), port("9090")),
                                List.of(),
                                NOW,
                                current))
                .isEmpty();
    }

    @Test
    void a_port_that_left_the_file_closes_at_the_scan_time_and_the_other_stays() {
        Fact port8080 = existing(SCAN, "8080").id("p8080").key(PORT).build();
        Fact port9090 = existing(SCAN, "9090").id("p9090").key(PORT).build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(port("9090")),
                        List.of(),
                        NOW,
                        List.of(sourced(port8080), sourced(port9090)));

        assertThat(closed).extracting(Fact::id).containsExactly("p8080");
        assertThat(closed.getFirst().validTo()).isEqualTo(NOW);
    }

    /**
     * One value from two files: file A dropped 8080, but file B of the same scan still has it, so
     * it stays open whether B was read before A or after it. A value no file has still closes.
     */
    @Test
    void a_value_another_file_of_the_same_scan_still_reports_stays_open() {
        Fact port8080 = existing(SCAN, "8080").id("p8080").key(PORT).build();
        Fact port7070 = existing(SCAN, "7070").id("p7070").key(PORT).build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(port("9090")),
                        List.of(port("8080"), incoming(SCAN, "8080").key(PORT).env("test").build()),
                        NOW,
                        List.of(sourced(port8080), sourced(port7070)));

        assertThat(closed).extracting(Fact::id).containsExactly("p7070");
    }

    /** Backed elsewhere means the same key, env and value; another env does not keep it. */
    @Test
    void a_value_another_file_reports_in_another_env_still_closes() {
        Fact port8080 = existing(SCAN, "8080").id("p8080").key(PORT).build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(port("9090")),
                        List.of(incoming(SCAN, "8080").key(PORT).env("test").build()),
                        NOW,
                        List.of(sourced(port8080)));

        assertThat(closed).extracting(Fact::id).containsExactly("p8080");
    }

    /** A scan time before the fact began (clock skew, a late file) still ends no fact early. */
    @Test
    void a_scan_time_before_the_fact_began_never_ends_it_before_it_began() {
        Fact port8080 = existing(SCAN, "8080").id("p8080").key(PORT).build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(port("9090")),
                        List.of(),
                        LONG_AGO,
                        List.of(sourced(port8080)));

        assertThat(closed).extracting(Fact::id).containsExactly("p8080");
        assertThat(closed.getFirst().validTo()).isAfterOrEqualTo(FIRST_SEEN);
    }

    /** The review case: B waits as a conflict, the file now says C; B is stale and closes. */
    @Test
    void a_stale_waiting_conflict_from_the_same_file_closes_and_the_new_value_waits() {
        Fact userValue = existing(USER, "A").id("user-a").build();
        Fact waitingB = existing(SCAN, "B").id("scan-b").status(FactStatus.CONFLICT).build();
        Fact incomingC = incoming(SCAN, "C").build();
        List<Sourced> before = List.of(new Sourced(userValue, null), sourced(waitingB));

        var decision = WritePolicy.decide(new Sourced(incomingC, FILE_A), before, SINGLE);
        Fact waitingC = ((Conflict) decision).added();
        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(incomingC),
                        List.of(),
                        NOW,
                        List.of(before.get(0), before.get(1), sourced(waitingC)));

        assertThat(closed).extracting(Fact::id).containsExactly("scan-b");
    }

    @Test
    void only_open_scan_facts_of_that_file_and_of_a_scanned_slot_close() {
        List<Sourced> current =
                List.of(
                        new Sourced(existing(USER, "8080").id("user").key(PORT).build(), FILE_A),
                        new Sourced(
                                existing(SCAN, "8080").id("other-file").key(PORT).build(), FILE_B),
                        sourced(
                                existing(SCAN, "8080")
                                        .id("closed")
                                        .key(PORT)
                                        .validTo(LONG_AGO)
                                        .build()),
                        sourced(
                                existing(SCAN, "8080")
                                        .id("other-env")
                                        .key(PORT)
                                        .env("test")
                                        .build()),
                        sourced(existing(SCAN, "rocky").id("other-key").key("os").build()));

        assertThat(
                        WritePolicy.closedByRescan(
                                FILE_A, List.of(port("9090")), List.of(), NOW, current))
                .isEmpty();
    }

    private static Sourced sourced(Fact fact) {
        return new Sourced(fact, FILE_A);
    }

    private static Fact port(String value) {
        return incoming(SCAN, value).key(PORT).build();
    }

    private static Sourced scanOf(String value) {
        return new Sourced(port(value), FILE_A);
    }
}
