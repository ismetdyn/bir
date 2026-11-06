package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.DATABASE;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.write.GraphEdits.Resolution;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plan H5: an edit that cannot apply is a WriteRejectedException ({error, hint}, REST 400) and
 * leaves the graph exactly as it was. No message carries a secret (invariant 2): neither a
 * secret-looking text nor a value stored on a Secret may reach the summary, which goes into the
 * snapshot and git.
 */
class GraphEditsRefusalTest {

    private static final String UNKNOWN = "01KBTESTUNKNOWN00000000000";
    private static final String SECRET = "password=" + "Sup3rS3cretZZ7";
    private static final String STORED = "kbtestStoredValue4711";
    private static final String STORED_UPPER = "KBTESTSTOREDVALUE4711";
    private static final int SUMMARY_LIMIT = 300;
    private static final Consumer<EditFixture> NOTHING = f -> {};

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

    private static Arguments refused(
            String reason, Consumer<EditFixture> setup, Consumer<EditFixture> edit) {
        return arguments(reason, setup, edit);
    }

    /** Closes, entity edits and (un)archive (ADR-0025 decision 18). */
    static Stream<Arguments> factAndEntityRefusals() {
        return Stream.of(
                refused(
                        "closing a closed fact",
                        f -> f.edits().closeFact(f.osFact),
                        f -> f.edits().closeFact(f.osFact)),
                refused("closing an unknown fact", NOTHING, f -> f.edits().closeFact(UNKNOWN)),
                refused(
                        "closing a closed relation",
                        f -> f.edits().closeRelation(f.relation),
                        f -> f.edits().closeRelation(f.relation)),
                refused(
                        "closing an unknown relation",
                        NOTHING,
                        f -> f.edits().closeRelation(UNKNOWN)),
                refused(
                        "editing an unknown entity",
                        NOTHING,
                        f -> f.edits().editEntity("zz-nowhere", null, "kbtest")),
                refused(
                        "summary over 300 characters",
                        NOTHING,
                        f -> f.edits().editEntity(HOST, null, "ş".repeat(SUMMARY_LIMIT + 1))),
                refused(
                        "secret-looking summary",
                        NOTHING,
                        f -> f.edits().editEntity(HOST, null, "login " + SECRET)),
                refused(
                        "summary holding a stored secret value",
                        f -> f.graph.writes.putSecret(HOST + "/prod/kbtest-pw", STORED),
                        f -> f.edits().editEntity(HOST, null, "login " + STORED)),
                refused(
                        "alias holding a stored secret value",
                        f -> f.graph.writes.putSecret(HOST + "/prod/kbtest-pw", STORED),
                        f -> f.edits().editEntity(HOST, List.of("zz-" + STORED), null)),
                refused(
                        "alias holding a stored secret value in upper case",
                        f -> f.graph.writes.putSecret(HOST + "/prod/kbtest-pw", STORED),
                        f -> f.edits().editEntity(HOST, List.of(STORED_UPPER), null)),
                refused(
                        "alias that is another entity's id",
                        NOTHING,
                        f -> f.edits().editEntity(HOST, List.of(DATABASE), null)),
                refused(
                        "alias that is another entity's id in upper case",
                        NOTHING,
                        f -> f.edits().editEntity(HOST, List.of("ZZ-DB"), null)),
                refused("archiving an unknown entity", NOTHING, f -> f.edits().archive(UNKNOWN)),
                refused(
                        "archiving an archived entity",
                        NOTHING,
                        f -> f.edits().archive(EditFixture.RETIRED)),
                refused(
                        "unarchiving an entity that is not archived",
                        NOTHING,
                        f -> f.edits().unarchive(HOST)));
    }

    /**
     * Conflict resolutions and proposal reviews; a review note goes into the snapshot and is
     * checked like a summary (ADR-0025 decision 18).
     */
    static Stream<Arguments> conflictAndProposalRefusals() {
        return Stream.of(
                refused(
                        "resolving a fact that waits for nothing",
                        NOTHING,
                        f -> f.edits().resolveConflict(f.osFact, Resolution.ACCEPT_NEW)),
                refused(
                        "resolving an unknown fact",
                        NOTHING,
                        f -> f.edits().resolveConflict(UNKNOWN, Resolution.KEEP_OLD)),
                refused(
                        "resolving a conflict already resolved",
                        f -> f.edits().resolveConflict(f.waitingFact, Resolution.KEEP_OLD),
                        f -> f.edits().resolveConflict(f.waitingFact, Resolution.ACCEPT_NEW)),
                refused(
                        "accepting an accepted proposal",
                        f -> f.edits().acceptProposal(f.proposals.get("decision")),
                        f -> f.edits().acceptProposal(f.proposals.get("decision"))),
                refused(
                        "rejecting a rejected proposal",
                        f -> f.edits().rejectProposal(f.proposals.get("archive"), null),
                        f -> f.edits().rejectProposal(f.proposals.get("archive"), null)),
                refused(
                        "accepting a rejected proposal",
                        f -> f.edits().rejectProposal(f.proposals.get("archive"), null),
                        f -> f.edits().acceptProposal(f.proposals.get("archive"))),
                refused(
                        "accepting an unknown proposal",
                        NOTHING,
                        f -> f.edits().acceptProposal(UNKNOWN)),
                refused(
                        "secret-looking review note",
                        NOTHING,
                        f ->
                                f.edits()
                                        .rejectProposal(
                                                f.proposals.get("decision"), "see " + SECRET)),
                refused(
                        "review note holding a stored secret value",
                        f -> f.graph.writes.putSecret(HOST + "/prod/kbtest-pw", STORED),
                        f -> f.edits().rejectProposal(f.proposals.get("decision"), STORED)),
                refused(
                        "review note over 300 characters",
                        NOTHING,
                        f ->
                                f.edits()
                                        .rejectProposal(
                                                f.proposals.get("decision"),
                                                "ş".repeat(SUMMARY_LIMIT + 1))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"factAndEntityRefusals", "conflictAndProposalRefusals"})
    void a_refused_edit_changes_nothing_and_names_the_way_out(
            String reason, Consumer<EditFixture> setup, Consumer<EditFixture> edit) {
        setup.accept(fixture);
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(WriteRejectedException.class, () -> edit.accept(fixture));

        assertThat(rejected).as(reason).isNotNull();
        assertThat(rejected.hint()).as(reason).isNotBlank();
        assertThat(rejected.getMessage() + rejected.hint())
                .as(reason)
                .doesNotContainIgnoringCase(SECRET)
                .doesNotContainIgnoringCase(STORED);
        assertThat(fixture.graph.state()).as(reason).isEqualTo(before);
    }
}
