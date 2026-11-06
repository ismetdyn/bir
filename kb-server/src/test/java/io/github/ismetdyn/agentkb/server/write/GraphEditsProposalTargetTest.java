package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.DECISION_CONTENT;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.NEW_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decisions CP3 J3 and J4 on accepting a proposal (ADR-0025 decision 12): a Decision
 * APPLIES_TO a Stack or Project and is ABOUT another entity (SPEC §6.2), any other target is
 * refused, and no proposal is about a Revision. A schema key a failed commit already left in
 * facts.yaml does not block a second accept.
 */
class GraphEditsProposalTargetTest {

    private static final String STATUS = "status";
    private static final String PROJECT = "zz-project";

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

    private String acceptDecisionAbout(String target) {
        String id =
                fixture.graph.writes.propose(
                        new ProposalRequest(
                                Source.AGENT, "decision", target, DECISION_CONTENT, null));
        return fixture.edits().acceptProposal(id).batch();
    }

    private List<Map<String, Object>> decisionEdges(String batch) {
        return fixture.graph.rows(
                "MATCH (d:Decision {batch: $b})-[e]->(t) RETURN type(e) AS type, t.id AS target",
                Map.of("b", batch));
    }

    /** SPEC §6.2: APPLIES_TO goes to a Stack or Project, ABOUT to another entity (CP3 J4). */
    @Test
    void a_decision_applies_to_a_project_and_is_about_another_entity() {
        fixture.graph
                .writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(
                                        new Node(
                                                PROJECT,
                                                List.of("Entity", "Project"),
                                                Map.of("status", "current"))),
                                List.of()));

        assertThat(decisionEdges(acceptDecisionAbout(PROJECT)))
                .containsExactly(Map.of("type", "APPLIES_TO", "target", PROJECT));
        assertThat(decisionEdges(acceptDecisionAbout(HOST)))
                .containsExactly(Map.of("type", "ABOUT", "target", HOST));
    }

    /** A Decision points to nothing but a Stack, Project or entity (SPEC §6.2). */
    @Test
    void accepting_a_decision_about_a_record_that_is_no_entity_is_refused() {
        String id =
                fixture.graph.writes.propose(
                        new ProposalRequest(
                                Source.AGENT, "decision", fixture.osFact, DECISION_CONTENT, null));
        Map<String, Object> before = fixture.graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class, () -> fixture.edits().acceptProposal(id));

        assertThat(rejected).isNotNull();
        assertThat(rejected.hint()).isNotBlank();
        assertThat(fixture.graph.state()).isEqualTo(before);
    }

    /** A Revision is bookkeeping of an edit, nothing a proposal changes (CP3 J4). */
    @Test
    void a_proposal_about_a_revision_is_refused() {
        EditResult edit = fixture.edits().editEntity(HOST, null, "kbtest summary");
        String revision =
                (String)
                        fixture.graph
                                .rows(
                                        "MATCH (rv:Revision {batch: $b}) RETURN rv.id AS id",
                                        Map.of("b", edit.batch()))
                                .getFirst()
                                .get("id");
        ProposalRequest request =
                new ProposalRequest(Source.AGENT, "archive", revision, "kbtest archive it", null);

        assertThat(
                        catchThrowableOfType(
                                WriteRejectedException.class,
                                () -> fixture.graph.writes.propose(request)))
                .isNotNull();
    }

    /**
     * CP3 J3: the file is written before the graph commits. A key a failed commit left in
     * facts.yaml with the same rules does not block a second accept, and is not written twice.
     */
    @Test
    void accepting_a_schema_proposal_whose_key_is_already_written_accepts_it() throws IOException {
        Path facts = dir.resolve("repo/schema/facts.yaml");
        String written =
                Files.readString(facts)
                        .replace("facts:\n", "facts:\n  " + NEW_KEY + ":   {multi: false}\n");
        Files.writeString(facts, written);

        fixture.edits().acceptProposal(fixture.proposals.get("schema"));

        assertThat(fixture.node(fixture.proposals.get("schema"))).containsEntry(STATUS, "accepted");
        assertThat(Files.readString(facts)).isEqualTo(written);
    }
}
