package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T1;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0025 decision 16 through the write path: a value the user closed stays closed. user_stated,
 * scan or agent writing it again leaves it waiting as a conflict; only a user write opens it again,
 * as a new fact. The fixture's os fact (user, rhel 9, valid_from T1) is the closed value.
 */
class GraphEditsTombstoneTest {

    private static final String OS = "os";
    private static final String CLOSED_VALUE = "rhel 9";

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

    private WriteResult write(Source source, String value) {
        return fixture.graph.write(request(source, HOST, now(), fact(OS, value, PROD)));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_lower_source_writing_the_closed_value_leaves_it_waiting_as_a_conflict(Source source) {
        fixture.edits().closeFact(fixture.osFact);

        WriteResult result = write(source, CLOSED_VALUE);

        assertThat(result.conflicts()).hasSize(1);
        assertThat(fixture.graph.openFacts(HOST, OS, PROD))
                .singleElement()
                .satisfies(
                        open ->
                                assertThat(open)
                                        .containsEntry("value", CLOSED_VALUE)
                                        .containsEntry("status", "conflict"));
    }

    @Test
    void the_user_writing_the_closed_value_opens_it_as_a_new_current_fact() {
        fixture.edits().closeFact(fixture.osFact);
        Instant at = now();

        WriteResult result =
                fixture.graph.write(request(Source.USER, HOST, at, fact(OS, CLOSED_VALUE, PROD)));

        assertThat(result.added()).singleElement().isNotEqualTo(fixture.osFact);
        assertThat(fixture.graph.openFacts(HOST, OS, PROD))
                .singleElement()
                .satisfies(
                        open ->
                                assertThat(open)
                                        .containsEntry("source", "user")
                                        .containsEntry("status", "ok")
                                        .containsEntry("valid_from", Times.format(at)));
        assertThat(fixture.node(fixture.osFact))
                .as("the closed fact keeps its range")
                .containsEntry("valid_from", Times.format(T1))
                .containsKey("valid_to");
    }

    /** ADR-0022 decision 23: the user resolves the waiting value; its valid_from is carried. */
    @Test
    void the_user_writing_a_waiting_closed_value_carries_the_waiting_valid_from() {
        fixture.edits().closeFact(fixture.osFact);
        String waiting = write(Source.AGENT, CLOSED_VALUE).conflicts().getFirst();
        Object waitingSince = fixture.node(waiting).get("valid_from");

        write(Source.USER, CLOSED_VALUE);

        List<Map<String, Object>> open = fixture.graph.openFacts(HOST, OS, PROD);
        assertThat(open)
                .singleElement()
                .satisfies(
                        current ->
                                assertThat(current)
                                        .containsEntry("source", "user")
                                        .containsEntry("status", "ok")
                                        .containsEntry("valid_from", waitingSince));
        assertThat(fixture.node(waiting)).containsKey("valid_to");
    }

    /**
     * Reopening a close must not leave two open values in its slot: a later batch that wrote into
     * the slot goes first (BatchRevert's no-partial-revert rule).
     */
    @ParameterizedTest
    @ValueSource(strings = {CLOSED_VALUE, "rhel 10"})
    void a_close_with_a_later_write_into_its_slot_is_not_reverted_first(String value) {
        EditResult close = fixture.edits().closeFact(fixture.osFact);
        WriteResult later = write(Source.AGENT, value);
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(close.batch()));

        assertThat(rejected).isNotNull();
        assertThat(rejected.hint()).contains(later.batch());
        assertThat(fixture.graph.state()).isEqualTo(before);
    }
}
