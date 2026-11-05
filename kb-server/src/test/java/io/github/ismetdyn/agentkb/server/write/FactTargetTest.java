package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SPEC §6 TARGET: a port fact points to the entity it serves. The target is part of the value
 * (ADR-0022 decision 30), so the same port with another target is another value and SPEC §7 rules
 * 2-3 apply: an equal or higher source closes the old fact and opens a new one, a lower one waits
 * as a conflict. A fact never changes in place (decisions 22-23).
 */
class FactTargetTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String APP = "zz-app";
    private static final String DB_A = "zz-db-a";
    private static final String DB_B = "zz-db-b";
    private static final String PORT = "port";
    private static final String VALUE = "5432/tcp";
    private static final String TARGETS =
            "MATCH (:Entity {id: $app})-[:HAS_FACT]->(f:Fact {key: 'port'})-[:TARGET]->(t)"
                    + " RETURN f.id AS fact, t.id AS target, f.valid_to IS NULL AS open";
    private static final String SUPERSEDED =
            "MATCH (n:Fact)-[:SUPERSEDES]->(o:Fact) RETURN n.id AS new, o.id AS old";
    private static final Map<String, Object> BY_APP = Map.of("app", APP);
    private static final String CONFLICT_SIGNALS =
            "MATCH (s:Signal {kind: 'conflict'})-[:ABOUT]->(:Entity {id: $app})"
                    + " RETURN count(s) AS n";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(request(Source.USER, DB_A, T1, fact("os", "rhel 9", PROD)));
        graph.write(request(Source.USER, DB_B, T1, fact("os", "rhel 9", PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    private String openPort() {
        return (String) graph.openFacts(APP, PORT, PROD).getFirst().get("id");
    }

    @Test
    void a_port_with_a_target_points_to_that_entity() {
        graph.write(request(Source.AGENT, APP, T1, fact(PORT, VALUE, PROD, DB_A)));

        assertThat(graph.rows(TARGETS, BY_APP))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row).containsEntry("fact", openPort());
                            assertThat(row).containsEntry("target", DB_A);
                        });
    }

    /**
     * Decision 30: the target is part of the value, so a write without one confirms the port number
     * but not where it points. A higher source must not lift a target that a lower source set to
     * its own trust: the old fact is only refreshed and keeps its source and target.
     */
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"AGENT, USER", "SCAN, USER_STATED", "USER_STATED, USER"})
    void a_higher_source_without_a_target_only_refreshes_a_target_a_lower_source_set(
            Source first, Source second) {
        graph.write(request(first, APP, T1, fact(PORT, VALUE, PROD, DB_A)));
        String old = openPort();

        WriteResult result = graph.write(request(second, APP, T2, fact(PORT, VALUE, PROD)));

        assertThat(result.refreshed()).containsExactly(old);
        assertThat(result.added()).isEmpty();
        assertThat(result.closed()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(graph.openFacts(APP, PORT, PROD))
                .singleElement()
                .satisfies(
                        open ->
                                assertThat(open)
                                        .containsEntry("id", old)
                                        .containsEntry("source", Ids.normalize(first.name()))
                                        .containsEntry("observed_at", Times.format(T2)));
        assertThat(graph.rows(TARGETS, BY_APP))
                .extracting(row -> List.of(row.get("fact"), row.get("target"), row.get("open")))
                .containsExactly(List.of(old, DB_A, true));
        assertThat(graph.rows(SUPERSEDED, Map.of())).isEmpty();
    }

    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"AGENT, AGENT", "USER_STATED, AGENT"})
    void the_same_or_a_lower_source_with_the_same_target_only_refreshes(
            Source first, Source second) {
        graph.write(request(first, APP, T1, fact(PORT, VALUE, PROD, DB_A)));
        String old = openPort();

        WriteResult result = graph.write(request(second, APP, T2, fact(PORT, VALUE, PROD, DB_A)));

        assertThat(result.refreshed()).containsExactly(old);
        assertThat(result.added()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(graph.rows(TARGETS, BY_APP))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("target", DB_A));
    }

    /**
     * ADR-0022 decision 30, SPEC §7 rule 2: another target is another value, so an equal or higher
     * source closes the old port and opens a new one linked with SUPERSEDES. Equal sources used to
     * be rejected here.
     */
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"USER_STATED, USER_STATED", "AGENT, AGENT", "AGENT, USER", "SCAN, USER_STATED"})
    void an_equal_or_higher_source_with_another_target_closes_the_old_port_and_opens_a_new_one(
            Source first, Source second) {
        graph.write(request(first, APP, T1, fact(PORT, VALUE, PROD, DB_A)));
        String old = openPort();

        WriteResult result = graph.write(request(second, APP, T2, fact(PORT, VALUE, PROD, DB_B)));

        assertThat(graph.openFacts(APP, PORT, PROD)).hasSize(1);
        String changed = openPort();
        assertThat(result.closed()).containsExactly(old);
        assertThat(result.added()).containsExactly(changed);
        assertThat(result.conflicts()).isEmpty();
        assertThat(graph.rows(TARGETS, BY_APP))
                .extracting(row -> List.of(row.get("fact"), row.get("target"), row.get("open")))
                .containsExactlyInAnyOrder(List.of(old, DB_A, false), List.of(changed, DB_B, true));
        assertThat(graph.rows(SUPERSEDED, Map.of()))
                .containsExactly(Map.of("new", changed, "old", old));
    }

    /**
     * Decision 30, SPEC §7 rule 3: a lower source with another target waits as a conflict and the
     * old port stays current. An empty first target means the old port points nowhere.
     */
    @ParameterizedTest(name = "{0} {1} then {2} {3}")
    @CsvSource({
        "USER_STATED, zz-db-a, AGENT, zz-db-b",
        "USER, zz-db-a, USER_STATED, zz-db-b",
        "SCAN, , AGENT, zz-db-a"
    })
    void a_lower_source_with_another_target_waits_as_a_conflict_and_the_old_port_stays(
            Source first, String firstTarget, Source second, String secondTarget) {
        graph.write(request(first, APP, T1, fact(PORT, VALUE, PROD, firstTarget)));
        String old = openPort();

        WriteResult result =
                graph.write(request(second, APP, T2, fact(PORT, VALUE, PROD, secondTarget)));

        List<Map<String, Object>> open = graph.openFacts(APP, PORT, PROD);
        assertThat(open).hasSize(2);
        Map<String, Object> kept =
                open.stream().filter(f -> old.equals(f.get("id"))).findFirst().orElseThrow();
        Map<String, Object> waiting =
                open.stream().filter(f -> !old.equals(f.get("id"))).findFirst().orElseThrow();
        assertThat(kept.get("status")).isNotEqualTo("conflict");
        assertThat(waiting).containsEntry("status", "conflict").containsEntry("value", VALUE);
        assertThat(result.closed()).isEmpty();
        assertThat(result.conflicts()).containsExactly((String) waiting.get("id"));
        assertThat(graph.rows(TARGETS, BY_APP))
                .filteredOn(row -> waiting.get("id").equals(row.get("fact")))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("target", secondTarget));
        assertThat(graph.rows(CONFLICT_SIGNALS, BY_APP))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("n", 1L));
    }

    /** SPEC §7 rule 5: another port number is another value of a multi-valued key; it adds. */
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"USER, AGENT", "AGENT, USER"})
    void another_port_number_adds_whatever_the_sources(Source first, Source second) {
        graph.write(request(first, APP, T1, fact(PORT, VALUE, PROD, DB_A)));

        WriteResult result =
                graph.write(request(second, APP, T2, fact(PORT, "6432/tcp", PROD, DB_B)));

        assertThat(result.closed()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(graph.rows(TARGETS, BY_APP))
                .extracting(row -> List.of(row.get("target"), row.get("open")))
                .containsExactlyInAnyOrder(List.of(DB_A, true), List.of(DB_B, true));
    }

    @Test
    void a_missing_target_entity_rejects_the_whole_write() {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                APP,
                                                T1,
                                                fact("os", "rhel 9", PROD),
                                                fact(PORT, VALUE, PROD, "zz-nowhere"))));

        assertThat(rejected).isNotNull();
        assertThat(rejected.hint()).contains("kb_search");
        assertThat(graph.state()).isEqualTo(before);
    }
}
