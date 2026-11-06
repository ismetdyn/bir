package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.DECISION_CONTENT;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.DECISION_RULE;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.NEW_KEY;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.STACK;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T2;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code POST /proposals/{id}/accept|reject} (ADR-0025 decision 12, ADR-0022 decision 31): both set
 * the Proposal's status for every kind. Only an accept acts: archive archives its target, decision
 * opens a Decision (rule = first line, body = content, APPLIES_TO the target), schema writes the
 * key into schema/facts.yaml and the schema is loaded again; rule-change only changes the status. A
 * reject changes nothing but the status.
 */
class GraphEditsProposalTest {

    private static final String STATUS = "status";

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

    @ParameterizedTest
    @ValueSource(strings = {"decision", "rule-change", "archive", "schema"})
    void rejecting_a_proposal_of_any_kind_changes_only_its_status(String kind) {
        String id = fixture.proposals.get(kind);
        Map<String, Object> before = withoutNode(fixture.graph.state(), id);

        fixture.edits().rejectProposal(id, null);

        assertThat(fixture.node(id)).containsEntry(STATUS, "rejected");
        assertThat(withoutNode(fixture.graph.state(), id)).isEqualTo(before);
    }

    /** ADR-0025 decision 18: {@code kb reject -m} keeps the note on the proposal, stripped. */
    @Test
    void rejecting_with_a_note_keeps_it_as_review_note() {
        String id = fixture.proposals.get("decision");

        fixture.edits().rejectProposal(id, "  Kbtest: we keep plain-text logs.  ");

        assertThat(fixture.node(id))
                .containsEntry(STATUS, "rejected")
                .containsEntry("review_note", "Kbtest: we keep plain-text logs.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "rule-change", "archive", "schema"})
    void accepting_a_proposal_of_any_kind_sets_its_status(String kind) {
        String id = fixture.proposals.get(kind);

        fixture.edits().acceptProposal(id);

        assertThat(fixture.node(id)).containsEntry(STATUS, "accepted");
    }

    @Test
    void accepting_a_rule_change_changes_only_its_status() {
        String id = fixture.proposals.get("rule-change");
        Map<String, Object> before = withoutNode(fixture.graph.state(), id);

        fixture.edits().acceptProposal(id);

        assertThat(withoutNode(fixture.graph.state(), id)).isEqualTo(before);
    }

    @Test
    void accepting_an_archive_proposal_archives_its_target_and_keeps_its_facts() {
        int facts = fixture.graph.facts(HOST).size();

        fixture.edits().acceptProposal(fixture.proposals.get("archive"));

        assertThat(fixture.node(HOST)).containsEntry(STATUS, "archived");
        assertThat(fixture.graph.facts(HOST)).hasSize(facts);
    }

    @Test
    void accepting_a_decision_proposal_opens_a_decision_that_applies_to_its_target() {
        EditResult result = fixture.edits().acceptProposal(fixture.proposals.get("decision"));

        assertThat(
                        fixture.graph.rows(
                                "MATCH (d:Decision {batch: $b})-[:APPLIES_TO]->(t)"
                                        + " RETURN d.rule AS rule, d.body AS body, t.id AS target",
                                Map.of("b", result.batch())))
                .containsExactly(
                        Map.of("rule", DECISION_RULE, "body", DECISION_CONTENT, "target", STACK));
    }

    /** SPEC §7 rule 11: the key was refused before the accept and is written after it. */
    @Test
    void accepting_a_schema_proposal_adds_its_key_to_the_schema() throws IOException {
        WriteRequest write = request(Source.USER, HOST, T2, fact(NEW_KEY, "r12-u3", PROD));
        assertThat(
                        catchThrowableOfType(
                                WriteRejectedException.class, () -> fixture.graph.write(write)))
                .as("precondition: unknown key")
                .isNotNull();

        fixture.edits().acceptProposal(fixture.proposals.get("schema"));

        assertThat(Files.readString(dir.resolve("repo/schema/facts.yaml"))).contains(NEW_KEY);
        fixture.graph.write(write);
        assertThat(fixture.graph.openFacts(HOST, NEW_KEY, PROD)).hasSize(1);
    }

    // Also without the Revision rows that keep the old status (ADR-0025 decision 15).
    private static Map<String, Object> withoutNode(Map<String, Object> state, String id) {
        state.remove("node " + id);
        return EditFixture.withoutRevisions(state);
    }
}
