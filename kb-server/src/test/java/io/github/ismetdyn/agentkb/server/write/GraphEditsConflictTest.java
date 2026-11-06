package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T2;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.WAITING_HOSTNAME;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.GraphEdits.Resolution;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code POST /conflicts/{id}/resolve} (ADR-0025 decision 11): accept-new rewrites the waiting
 * value as the user's through ADR-0022 decision 23 (waiting and current close, one new current fact
 * with the waiting valid_from, SUPERSEDES to both); keep-old closes only the waiting fact. The
 * conflict signal stays open until F4 (ADR-0022 decision 7). The user's value then outranks every
 * later lower-trust write (SPEC §7 rule 3).
 */
class GraphEditsConflictTest {

    private static final String HOSTNAME = "hostname";
    private static final String SIGNALS =
            "MATCH (s:Signal) RETURN properties(s) AS s ORDER BY s.id";

    @TempDir Path dir;

    private EditFixture fixture;

    @BeforeEach
    void openGraph() throws IOException {
        fixture = EditFixture.open(dir);
    }

    @AfterEach
    void closeGraph() {
        fixture.close();
    }

    @Test
    void accept_new_makes_the_waiting_value_the_users_current_value() {
        Map<String, Object> waiting = fixture.node(fixture.waitingFact);

        EditResult result =
                fixture.edits().resolveConflict(fixture.waitingFact, Resolution.ACCEPT_NEW);

        assertThat(fixture.graph.openFacts(HOST, HOSTNAME, PROD))
                .singleElement()
                .satisfies(
                        current ->
                                assertThat(current)
                                        .containsEntry("value", WAITING_HOSTNAME)
                                        .containsEntry("source", "user")
                                        .containsEntry("status", "ok")
                                        .containsEntry("batch", result.batch())
                                        .containsEntry("valid_from", waiting.get("valid_from")));
        for (String closed : List.of(fixture.waitingFact, fixture.currentHostnameFact)) {
            assertThat(fixture.node(closed))
                    .as(closed)
                    .containsKey("valid_to")
                    .containsEntry("closed_batch", result.batch());
        }
        assertThat(
                        fixture.graph.rows(
                                "MATCH (n:Fact {batch: $b})-[:SUPERSEDES]->(old) RETURN old.id AS id",
                                Map.of("b", result.batch())))
                .extracting(row -> row.get("id"))
                .containsExactlyInAnyOrder(fixture.waitingFact, fixture.currentHostnameFact);
    }

    @Test
    void keep_old_closes_the_waiting_value_and_leaves_the_current_one_untouched() {
        Map<String, Object> current = fixture.node(fixture.currentHostnameFact);

        EditResult result =
                fixture.edits().resolveConflict(fixture.waitingFact, Resolution.KEEP_OLD);

        assertThat(fixture.node(fixture.currentHostnameFact)).isEqualTo(current);
        assertThat(fixture.node(fixture.waitingFact))
                .containsKey("valid_to")
                .containsEntry("closed_batch", result.batch());
        assertThat(fixture.graph.openFacts(HOST, HOSTNAME, PROD))
                .extracting(f -> f.get("id"))
                .containsExactly(fixture.currentHostnameFact);
    }

    @ParameterizedTest
    @EnumSource(Resolution.class)
    void the_conflict_signal_stays_open_and_unchanged(Resolution resolution) {
        List<Map<String, Object>> signals = fixture.graph.rows(SIGNALS, Map.of());
        assertThat(signals).as("precondition: the fixture's conflict").hasSize(1);

        fixture.edits().resolveConflict(fixture.waitingFact, resolution);

        assertThat(fixture.graph.rows(SIGNALS, Map.of())).isEqualTo(signals);
    }

    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_lower_trust_write_cannot_replace_the_value_the_user_accepted(Source source) {
        fixture.edits().resolveConflict(fixture.waitingFact, Resolution.ACCEPT_NEW);

        fixture.graph.write(request(source, HOST, T2, fact(HOSTNAME, "h3.example", PROD)));

        List<Map<String, Object>> open = fixture.graph.openFacts(HOST, HOSTNAME, PROD);
        assertThat(open)
                .filteredOn(f -> !"conflict".equals(f.get("status")))
                .extracting(f -> f.get("value"))
                .containsExactly(WAITING_HOSTNAME);
        assertThat(open)
                .filteredOn(f -> "h3.example".equals(f.get("value")))
                .extracting(f -> f.get("status"))
                .containsExactly("conflict");
    }
}
