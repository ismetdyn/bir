package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T1;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.ResultList;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.core.render.ResultList.Tier;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code POST /facts/{id}/close} and {@code /relations/{id}/close} (CLIENTS §1, ADR-0025 decision
 * 10): a close writes valid_to and closed_batch and nothing else; valid_from never moves (SPEC
 * §6.3). closed_batch goes through the snapshot like any property (invariant 8), and a closed or
 * archived record is still found, marked as history (invariant 12).
 */
class GraphEditsCloseTest {

    private static final String VALID_TO = "valid_to";
    private static final String CLOSED_BATCH = "closed_batch";
    private static final Set<String> CLOSE_PROPS = Set.of(VALID_TO, CLOSED_BATCH, "updated_at");

    @TempDir Path dir;
    @TempDir Path first;
    @TempDir Path second;

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
    void closing_a_fact_writes_valid_to_and_closed_batch_and_changes_nothing_else() {
        Map<String, Object> before = fixture.node(fixture.osFact);

        EditResult result = fixture.edits().closeFact(fixture.osFact);

        Map<String, Object> after = fixture.node(fixture.osFact);
        assertThat(after).containsEntry(CLOSED_BATCH, result.batch()).containsKey(VALID_TO);
        assertThat((String) after.get(VALID_TO))
                .isGreaterThanOrEqualTo((String) before.get("valid_from"));
        assertThat(GraphRows.without(after, CLOSE_PROPS))
                .as("valid_from, value and the batch that opened it stay")
                .isEqualTo(GraphRows.without(before, CLOSE_PROPS));
        assertThat(fixture.graph.openFacts(HOST, "os", PROD)).isEmpty();
    }

    @Test
    void closing_a_relation_writes_valid_to_and_closed_batch_and_changes_nothing_else() {
        Map<String, Object> before = fixture.edge(fixture.relation);

        EditResult result = fixture.edits().closeRelation(fixture.relation);

        Map<String, Object> after = fixture.edge(fixture.relation);
        assertThat(after).containsEntry(CLOSED_BATCH, result.batch()).containsKey(VALID_TO);
        assertThat(GraphRows.without(after, CLOSE_PROPS))
                .isEqualTo(GraphRows.without(before, CLOSE_PROPS));
    }

    /**
     * Invariant 8: the snapshot carries closed_batch, the same graph gives the same bytes after a
     * restore, and the restored close can still be reverted (decision 10).
     */
    @Test
    void closed_batch_goes_through_a_snapshot_round_trip_and_still_reverts() throws IOException {
        EditResult fact = fixture.edits().closeFact(fixture.osFact);
        EditResult edge = fixture.edits().closeRelation(fixture.relation);
        Snapshot exported = new SnapshotExporter(fixture.graph.store).export();
        assertThat(exported.nodes())
                .filteredOn(node -> node.id().equals(fixture.osFact))
                .singleElement()
                .satisfies(
                        node -> assertThat(node.props()).containsEntry(CLOSED_BATCH, fact.batch()));
        assertThat(exported.edges())
                .filteredOn(e -> e.id().equals(fixture.relation))
                .singleElement()
                .satisfies(e -> assertThat(e.props()).containsEntry(CLOSED_BATCH, edge.batch()));
        SnapshotFormat.write(first, exported);

        fixture.graph.writes.maintenance().replaceAll(exported);
        SnapshotFormat.write(second, new SnapshotExporter(fixture.graph.store).export());

        for (String file : List.of(SnapshotFormat.NODES_FILE, SnapshotFormat.EDGES_FILE)) {
            assertThat(second.resolve(file)).hasSameBinaryContentAs(first.resolve(file));
        }
        fixture.graph.writes.maintenance().revert(fact.batch());
        assertThat(fixture.node(fixture.osFact)).doesNotContainKeys(VALID_TO, CLOSED_BATCH);
    }

    /**
     * Invariant 12, SPEC §6.3: search does not filter on valid_to or status; the current hit comes
     * first, the closed value and the archived entity after it, marked. kb_read keeps both.
     */
    @Test
    void a_closed_fact_and_an_archived_entity_are_still_found_after_the_current_one() {
        fixture.graph.write(request(Source.USER, "zz-live", T1, fact("os", "rhel 10", PROD)));
        fixture.graph.write(request(Source.USER, "zz-gone", T1, fact("os", "debian 12", PROD)));
        fixture.edits().editEntity("zz-gone", null, "rhel box retired in May");
        fixture.edits().archive("zz-gone");
        fixture.edits().closeFact(fixture.osFact);
        KnowledgeQueries queries = new KnowledgeQueries(fixture.graph.store);

        // The order every reader shows (ResultList.render); search itself does not sort.
        List<Hit> hits =
                queries.search("rhel", null, null).stream().sorted(ResultList.ORDER).toList();

        assertThat(hits).extracting(Hit::id).containsExactlyInAnyOrder("zz-live", HOST, "zz-gone");
        assertThat(hits.getFirst().id()).isEqualTo("zz-live");
        assertThat(hits.getFirst().tier()).isEqualTo(Tier.CURRENT);
        assertThat(hits.subList(1, hits.size())).allMatch(hit -> hit.tier() == Tier.HISTORY);
        assertThat(hits)
                .filteredOn(hit -> hit.id().equals("zz-gone"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.archived()).isTrue());
        assertThat(hits)
                .filteredOn(hit -> hit.id().equals(HOST))
                .singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).contains("eski ("));
        assertThat(queries.entity(HOST).orElseThrow().facts())
                .anySatisfy(view -> assertThat(view.fact().validTo()).isNotNull());
        assertThat(queries.entity("zz-gone").orElseThrow().status()).isEqualTo("archived");
    }
}
