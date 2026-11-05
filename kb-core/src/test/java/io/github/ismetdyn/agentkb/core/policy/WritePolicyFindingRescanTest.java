package io.github.ismetdyn.agentkb.core.policy;

import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_A;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.FILE_B;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.LONG_AGO;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.MULTI;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.NOW;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.existing;
import static io.github.ismetdyn.agentkb.core.policy.WritePolicyFixtures.incoming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Add;
import io.github.ismetdyn.agentkb.core.policy.WritePolicy.Sourced;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/SECRETS.md and CLAUDE.md invariant 6: a scan reports every plain-text password of a file as
 * one finding (multi-valued, seed/schema/facts.yaml), so a finding of the file that a rescan no
 * longer reports is no longer backed by the file and closes; one it still reports stays. Unlike
 * other keys this holds even when the rescan reports no finding at all, also when the file is gone
 * or the password was its only extracted item (the scan of the file is then empty).
 */
class WritePolicyFindingRescanTest {

    private static final String FINDING = "finding";
    private static final String PATH = "src/main/resources/application-prod.yml";
    private static final Fact FINDING_LINE_10 = open("f10", finding(10));
    private static final Fact FINDING_LINE_12 = open("f12", finding(12));

    private static Stream<Arguments> rescans() {
        return Stream.of(
                arguments(
                        "both passwords still in the file",
                        List.of(seen(finding(10)), seen(finding(12))),
                        List.of()),
                arguments(
                        "the password of line 10 moved to an environment variable",
                        List.of(seen(finding(12))),
                        List.of("f10")),
                arguments(
                        "both passwords moved, the datasource stays",
                        List.of(incoming(SCAN, "ORNEKPDB").key("db_name").build()),
                        List.of("f10", "f12")),
                arguments(
                        "the file was deleted or nothing else was extracted from it",
                        List.<Fact>of(),
                        List.of("f10", "f12")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rescans")
    void a_finding_the_rescan_of_its_file_no_longer_reports_closes_at_the_scan_time(
            String rescan, List<Fact> scanned, List<String> closedIds) {
        List<Sourced> current = List.of(inFileA(FINDING_LINE_10), inFileA(FINDING_LINE_12));

        List<Fact> closed = WritePolicy.closedByRescan(FILE_A, scanned, List.of(), NOW, current);

        assertThat(closed).extracting(Fact::id).containsExactlyInAnyOrderElementsOf(closedIds);
        assertThat(closed).allSatisfy(fact -> assertThat(fact.validTo()).isEqualTo(NOW));
    }

    /** The finding rule is the exception: another key the rescan did not report stays (rule 5). */
    @Test
    void a_rescan_without_findings_closes_the_finding_but_not_a_key_it_did_not_report() {
        Fact port = existing(SCAN, "8080").id("port").key("port").build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(incoming(SCAN, "ORNEKPDB").key("db_name").build()),
                        List.of(),
                        NOW,
                        List.of(inFileA(FINDING_LINE_10), inFileA(port)));

        assertThat(closed).extracting(Fact::id).containsExactly("f10");
    }

    /** A finding in an env the rescan did not report is no more backed by the file than another. */
    @Test
    void a_finding_in_an_env_the_rescan_did_not_report_closes_too() {
        Fact testFinding =
                existing(SCAN, finding(20)).id("f20-test").key(FINDING).env("test").build();

        List<Fact> closed =
                WritePolicy.closedByRescan(
                        FILE_A,
                        List.of(seen(finding(10))),
                        List.of(),
                        NOW,
                        List.of(inFileA(FINDING_LINE_10), inFileA(testFinding)));

        assertThat(closed).extracting(Fact::id).containsExactly("f20-test");
    }

    @Test
    void only_open_scan_findings_of_the_rescanned_file_close() {
        List<Sourced> current =
                List.of(
                        new Sourced(open("other-file", finding(3)), FILE_B),
                        new Sourced(
                                existing(USER, finding(10)).id("user").key(FINDING).build(),
                                FILE_A),
                        inFileA(
                                existing(SCAN, finding(11))
                                        .id("closed")
                                        .key(FINDING)
                                        .validTo(LONG_AGO)
                                        .build()));

        assertThat(WritePolicy.closedByRescan(FILE_A, List.of(), List.of(), NOW, current))
                .isEmpty();
    }

    /** Multi-valued: a second password line of the same file adds its own finding. */
    @Test
    void a_second_finding_of_the_same_file_is_added_next_to_the_first() {
        var decision =
                WritePolicy.decide(
                        new Sourced(seen(finding(12)), FILE_A),
                        List.of(inFileA(FINDING_LINE_10)),
                        MULTI);

        assertThat(decision).isInstanceOf(Add.class);
        assertThat(((Add) decision).added().value()).isEqualTo(finding(12));
    }

    private static String finding(int line) {
        return "Düz metin parola bulundu: " + PATH + ":" + line + "; ortam değişkenine taşınmalı";
    }

    private static Fact open(String id, String value) {
        return existing(SCAN, value).id(id).key(FINDING).build();
    }

    private static Fact seen(String value) {
        return incoming(SCAN, value).key(FINDING).build();
    }

    private static Sourced inFileA(Fact fact) {
        return new Sourced(fact, FILE_A);
    }
}
