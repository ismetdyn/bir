package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.REVISES;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.REVISION;
import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0025 decision 15: an in-place edit first keeps the old values in a Revision node of its batch
 * (REVISES the record), so reverting edits newest first walks back through them; a revision goes
 * into the snapshot like any record. A later batch that revised the same record goes first
 * (BatchRevert's no-partial-revert rule). Decision 17: a schema accept is not reverted.
 */
class GraphEditsRevisionTest {

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
    void reverting_two_edits_newest_first_gives_back_each_earlier_value() {
        Map<String, Object> original = fixture.graph.stateWithoutRevertMarkers(UPDATED_AT);
        EditResult first = fixture.edits().editEntity(HOST, List.of("zz-first"), "first summary");
        Map<String, Object> afterFirst = fixture.graph.stateWithoutRevertMarkers(UPDATED_AT);
        EditResult second =
                fixture.edits().editEntity(HOST, List.of("zz-second"), "second summary");

        fixture.graph.writes.maintenance().revert(second.batch());

        assertThat(fixture.node(HOST))
                .containsEntry("aliases", List.of("zz-first"))
                .containsEntry("summary", "first summary");
        assertThat(fixture.graph.stateWithoutRevertMarkers(UPDATED_AT)).isEqualTo(afterFirst);

        fixture.graph.writes.maintenance().revert(first.batch());

        assertThat(fixture.node(HOST)).doesNotContainKeys("aliases", "summary");
        assertThat(fixture.graph.stateWithoutRevertMarkers(UPDATED_AT)).isEqualTo(original);
    }

    private static Arguments pair(
            String name,
            Function<EditFixture, EditResult> earlier,
            Function<EditFixture, EditResult> later) {
        return arguments(name, earlier, later);
    }

    static Stream<Arguments> editsOfOneRecord() {
        return Stream.of(
                pair(
                        "edit, edit",
                        f -> f.edits().editEntity(HOST, null, "first summary"),
                        f -> f.edits().editEntity(HOST, List.of("zz-later"), null)),
                pair(
                        "archive, unarchive",
                        f -> f.edits().archive(HOST),
                        f -> f.edits().unarchive(HOST)),
                pair(
                        "edit, archive",
                        f -> f.edits().editEntity(HOST, null, "first summary"),
                        f -> f.edits().archive(HOST)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsOfOneRecord")
    void an_edit_with_a_later_edit_of_the_same_record_is_not_reverted_first(
            String name,
            Function<EditFixture, EditResult> earlier,
            Function<EditFixture, EditResult> later) {
        EditResult first = earlier.apply(fixture);
        EditResult second = later.apply(fixture);
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(first.batch()));

        assertThat(rejected).as(name).isNotNull();
        assertThat(rejected.hint()).as(name).contains(second.batch());
        assertThat(fixture.graph.state()).as(name).isEqualTo(before);
    }

    /** Invariant 8: the revision is a snapshot row and still reverts after a restore. */
    @Test
    void a_revision_goes_through_the_snapshot_and_still_reverts_after_a_restore() {
        fixture.edits().editEntity(HOST, null, "first summary");
        EditResult second = fixture.edits().editEntity(HOST, null, "second summary");

        Snapshot exported = new SnapshotExporter(fixture.graph.store).export();

        assertThat(exported.nodes())
                .filteredOn(node -> node.labels().equals(List.of(REVISION)))
                .filteredOn(node -> second.batch().equals(node.props().get("batch")))
                .singleElement()
                .satisfies(
                        node -> {
                            assertThat(node.props()).containsEntry("summary", "first summary");
                            assertThat(exported.edges())
                                    .filteredOn(edge -> edge.from().equals(node.id()))
                                    .singleElement()
                                    .satisfies(
                                            edge -> {
                                                assertThat(edge.type()).isEqualTo(REVISES);
                                                assertThat(edge.to()).isEqualTo(HOST);
                                            });
                        });

        fixture.graph.writes.maintenance().replaceAll(exported);
        assertThat(new SnapshotExporter(fixture.graph.store).export()).isEqualTo(exported);
        fixture.graph.writes.maintenance().revert(second.batch());

        assertThat(fixture.node(HOST)).containsEntry("summary", "first summary");
    }

    /** A revision is no search hit: only records of the fulltext labels are. */
    @Test
    void a_revision_is_not_found_by_search() {
        fixture.edits().editEntity(HOST, null, "kbtestfirstword box");
        fixture.edits().editEntity(HOST, null, "second box");

        assertThat(new KnowledgeQueries(fixture.graph.store).search("kbtestfirstword", null, null))
                .isEmpty();
    }

    /**
     * Decision 17: the schema is normative (invariant 5) and lives in a file, so a schema accept is
     * undone by a new schema proposal, not by kb revert; nothing changes.
     */
    @Test
    void reverting_a_schema_accept_is_refused_and_changes_nothing() throws IOException {
        Path facts = dir.resolve("repo/schema/facts.yaml");
        EditResult accept = fixture.edits().acceptProposal(fixture.proposals.get("schema"));
        Map<String, Object> before = fixture.graph.state();
        String schema = Files.readString(facts);

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(accept.batch()));

        assertThat(rejected).isNotNull();
        assertThat(rejected.hint()).containsIgnoringCase("schema");
        assertThat(fixture.graph.state()).isEqualTo(before);
        assertThat(Files.readString(facts)).isEqualTo(schema);
    }
}
