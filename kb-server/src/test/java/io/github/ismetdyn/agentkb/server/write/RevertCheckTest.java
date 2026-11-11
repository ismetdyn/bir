package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code revertible} and {@code blockedBy} of the batch detail (RevertCheck) say what {@code kb
 * revert --batch} then does, for a schema accept (ADR-0025 decision 17) and for a batch a later
 * user edit built on (GraphEditsRevertTest).
 */
class RevertCheckTest {

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

    /**
     * Decision 17 as the batch detail shows it: a schema accept is not revertible, names no batch
     * that blocks it, and kb revert refuses it without a change.
     */
    @Test
    void a_schema_accept_is_not_revertible_and_revert_refuses_it() {
        String batch = fixture.edits().acceptProposal(fixture.proposals.get("schema")).batch();
        Map<String, Object> before = fixture.graph.state();

        RevertCheck check = fixture.graph.writes.maintenance().revertCheck(batch);
        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(batch));

        assertThat(check).isEqualTo(new RevertCheck(false, List.of()));
        assertThat(rejected).isNotNull();
        assertThat(fixture.graph.state()).isEqualTo(before);
    }

    /**
     * {@code revertible} says what revert then does: the blocked batch names the later one and is
     * refused; the later one goes, and then the first one goes too.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.ismetdyn.agentkb.server.write.GraphEditsRevertTest#closedLater")
    void revertible_agrees_with_the_revert(
            String name, Function<EditFixture, String[]> openAndClose) {
        String[] batches = openAndClose.apply(fixture);
        GraphMaintenance maintenance = fixture.graph.writes.maintenance();

        assertThat(maintenance.revertCheck(batches[0]))
                .as(name)
                .isEqualTo(new RevertCheck(false, List.of(batches[1])));
        assertThat(
                        catchThrowableOfType(
                                WriteRejectedException.class, () -> maintenance.revert(batches[0])))
                .as(name)
                .isNotNull();
        assertThat(maintenance.revertCheck(batches[1]))
                .as(name)
                .isEqualTo(new RevertCheck(true, List.of()));
        maintenance.revert(batches[1]);
        assertThat(maintenance.revertCheck(batches[0]))
                .as(name)
                .isEqualTo(new RevertCheck(true, List.of()));
        maintenance.revert(batches[0]);
    }
}
