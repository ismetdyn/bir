package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.RETIRED;
import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.GraphEdits.Resolution;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0025 decisions 9-12 and 15 with plan H5: every user edit is one batch, and {@code kb revert
 * --batch} of that batch gives back the graph as it was (decision 10: closed_batch is reopened too;
 * decision 15: the Revision of an in-place edit puts the old values back and goes). A schema accept
 * is the exception (decision 17). No edit deletes a record or moves a valid_from (invariant 5, SPEC
 * §6.3).
 *
 * <p>updated_at is left out of the revert comparison, as in BatchRevertTest: it is the technical
 * sync time and a revert writes a new one on what it reopens. So is the RevertedBatch node.
 */
class GraphEditsRevertTest {

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

    private static Arguments edit(String name, Function<EditFixture, EditResult> edit) {
        return arguments(name, edit);
    }

    static Stream<Arguments> edits() {
        return Stream.of(
                edit("close fact", f -> f.edits().closeFact(f.osFact)),
                edit("close relation", f -> f.edits().closeRelation(f.relation)),
                edit(
                        "edit aliases and summary",
                        f -> f.edits().editEntity(HOST, List.of("zz-host-alias"), "kbtest box")),
                edit("archive", f -> f.edits().archive(HOST)),
                edit("unarchive", f -> f.edits().unarchive(RETIRED)),
                edit(
                        "resolve accept-new",
                        f -> f.edits().resolveConflict(f.waitingFact, Resolution.ACCEPT_NEW)),
                edit(
                        "resolve keep-old",
                        f -> f.edits().resolveConflict(f.waitingFact, Resolution.KEEP_OLD)),
                edit(
                        "accept archive proposal",
                        f -> f.edits().acceptProposal(f.proposals.get("archive"))),
                edit(
                        "accept decision proposal",
                        f -> f.edits().acceptProposal(f.proposals.get("decision"))),
                edit(
                        "accept rule-change proposal",
                        f -> f.edits().acceptProposal(f.proposals.get("rule-change"))),
                edit(
                        "reject proposal",
                        f -> f.edits().rejectProposal(f.proposals.get("decision"), null)),
                edit(
                        "reject proposal with a note",
                        f ->
                                f.edits()
                                        .rejectProposal(
                                                f.proposals.get("rule-change"), "kbtest no")));
    }

    /** Decision 17: a schema accept is no batch kb revert undoes (GraphEditsRevisionTest). */
    static Stream<Arguments> allEdits() {
        return Stream.concat(
                edits(),
                Stream.of(
                        edit(
                                "accept schema proposal",
                                f -> f.edits().acceptProposal(f.proposals.get("schema")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edits")
    void reverting_the_batch_of_an_edit_gives_back_the_graph_as_it_was(
            String name, Function<EditFixture, EditResult> edit) {
        Map<String, Object> before = fixture.graph.state(UPDATED_AT);

        EditResult result = edit.apply(fixture);

        assertThat(result.batch()).as(name).matches(WriteHarness.ULID);
        assertThat(fixture.graph.state(UPDATED_AT))
                .as("precondition: " + name)
                .isNotEqualTo(before);

        fixture.graph.writes.maintenance().revert(result.batch());

        assertThat(fixture.graph.stateWithoutRevertMarkers(UPDATED_AT)).as(name).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEdits")
    void an_edit_deletes_no_record_and_moves_no_valid_from(
            String name, Function<EditFixture, EditResult> edit) {
        Map<String, Object> before = fixture.graph.state();

        edit.apply(fixture);

        Map<String, Object> after = fixture.graph.state();
        assertThat(after.keySet()).as(name).containsAll(before.keySet());
        before.forEach(
                (key, record) ->
                        assertThat(props(after.get(key)).get("valid_from"))
                                .as(name + ": valid_from of " + key)
                                .isEqualTo(props(record).get("valid_from")));
    }

    // A state() record ends with its properties (WriteHarness).
    private static Map<?, ?> props(Object record) {
        return (Map<?, ?>) ((List<?>) record).getLast();
    }

    private static final String SOLO = "zz-solo";

    /** A batch that opened a fact or a relation and the later user close of that record. */
    static Stream<Arguments> closedLater() {
        return Stream.of(
                arguments(
                        "fact",
                        (Function<EditFixture, String[]>)
                                f -> {
                                    WriteResult opened =
                                            f.graph.write(
                                                    request(
                                                            Source.USER,
                                                            SOLO,
                                                            EditFixture.T1,
                                                            fact("os", "rhel 9", PROD)));
                                    EditResult close =
                                            f.edits().closeFact(opened.added().getFirst());
                                    return new String[] {opened.batch(), close.batch()};
                                }),
                arguments(
                        "relation",
                        (Function<EditFixture, String[]>)
                                f -> {
                                    f.graph.write(
                                            request(
                                                    Source.USER,
                                                    SOLO,
                                                    EditFixture.T1,
                                                    fact("os", "rhel 9", PROD)));
                                    WriteResult opened =
                                            f.graph.write(
                                                    relation(
                                                            Source.USER,
                                                            SOLO,
                                                            EditFixture.T1,
                                                            "depends_on",
                                                            EditFixture.DATABASE));
                                    String edge =
                                            (String)
                                                    f.graph
                                                            .rows(
                                                                    "MATCH (:Entity {id: $from})"
                                                                            + "-[r:DEPENDS_ON]->()"
                                                                            + " RETURN r.id AS id",
                                                                    Map.of("from", SOLO))
                                                            .getFirst()
                                                            .get("id");
                                    EditResult close = f.edits().closeRelation(edge);
                                    return new String[] {opened.batch(), close.batch()};
                                }));
    }

    /**
     * CP3 J1: a later user close (closed_batch, no edge of its own) built on the record; reverting
     * the batch that opened it first would delete it and leave the close unrevertable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("closedLater")
    void a_batch_whose_record_a_later_user_edit_closed_is_not_reverted_first(
            String name, Function<EditFixture, String[]> openAndClose) {
        String[] batches = openAndClose.apply(fixture);
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () -> fixture.graph.writes.maintenance().revert(batches[0]));

        assertThat(rejected).as(name).isNotNull();
        assertThat(rejected.hint()).as(name).contains(batches[1]);
        assertThat(fixture.graph.state()).as(name).isEqualTo(before);
    }
}
