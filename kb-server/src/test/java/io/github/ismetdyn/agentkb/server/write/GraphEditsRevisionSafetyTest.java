package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.REVISES;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lead decisions CP3 S2 and S3 on the Revision of ADR-0025 decision 15: what it keeps has stored
 * secret values masked, and kb revert puts back only the fields an edit changes, so a Revision from
 * another machine cannot remove a secret value or move a valid_from.
 */
class GraphEditsRevisionSafetyTest {

    private static final String STORED = "kbtestStoredValue4711";
    private static final String IMPORTED_BATCH = "zz-imported-revision-batch";

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
     * CP3 S3: a Revision goes into the snapshot and git, so a value stored on a Secret that the
     * user edits away is masked in it, in its own case and in the alias form (Ids.normalize).
     */
    @Test
    void a_revision_keeps_a_stored_secret_value_masked() {
        fixture.edits().editEntity(HOST, List.of("zz-" + STORED), "login " + STORED);
        fixture.graph.writes.putSecret(HOST + "/prod/kbtest-pw", STORED);

        EditResult cleanup = fixture.edits().editEntity(HOST, List.of("zz-clean"), "clean");

        Map<String, Object> revision =
                GraphRows.props(
                        fixture.graph
                                .rows(
                                        "MATCH (rv:Revision {batch: $b}) RETURN properties(rv) AS p",
                                        Map.of("b", cleanup.batch()))
                                .getFirst()
                                .get("p"));
        assertThat(String.valueOf(revision))
                .doesNotContainIgnoringCase(STORED)
                .contains("login " + WriteService.MASK, "zz-" + WriteService.MASK);
    }

    static Stream<Arguments> fieldsOutsideTheAllowList() {
        return Stream.of(
                arguments("Secret", "secret_value"),
                arguments("Fact", "valid_from"),
                arguments("Entity", "episode"),
                arguments("Proposal", "kind"));
    }

    /**
     * CP3 S2: a Revision from another machine names fields; restore puts back only the fields an
     * edit changes (Entity aliases, summary, status; Proposal status, review_note). Anything else
     * refuses the whole revert and changes nothing.
     */
    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("fieldsOutsideTheAllowList")
    void a_revision_naming_another_field_is_not_restored(String label, String field) {
        String target = "zz-revised-" + field;
        fixture.graph
                .writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(
                                        new Node(
                                                target,
                                                List.of(label),
                                                "Secret".equals(label)
                                                        ? Map.of("name", "kbtest-pw")
                                                        : Map.of(field, "kbtest kept")),
                                        new Node(
                                                "zz-imported-revision",
                                                List.of(REVISION),
                                                Map.of(
                                                        "batch",
                                                        IMPORTED_BATCH,
                                                        "fields",
                                                        List.of(field)))),
                                List.of(
                                        new Edge(
                                                "zz-imported-revises",
                                                REVISES,
                                                "zz-imported-revision",
                                                target,
                                                Map.of("batch", IMPORTED_BATCH)))));
        if ("Secret".equals(label)) {
            fixture.graph.writes.putSecret(target, STORED);
        }
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(IMPORTED_BATCH));

        assertThat(rejected).isNotNull();
        assertThat(rejected.hint()).isNotBlank();
        assertThat(fixture.graph.state()).isEqualTo(before);
    }
}
