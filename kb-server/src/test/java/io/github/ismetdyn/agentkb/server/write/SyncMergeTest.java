package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos.edgeIds;
import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos.nodeIds;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.inBatch;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.untyped;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb sync} between two machines (SPEC §10, ARCHITECTURE §8, ADR-0022 decisions 41-42): A and
 * B start from the seed, each writes, then sync A, sync B, sync A. The merge is by id and lossless
 * (invariant 11): the newer updated_at wins; an equal one with other content goes to the larger
 * canonical row on both machines and opens one conflict signal; a reverted batch stays reverted.
 * Afterwards both graphs export the same snapshot and the remote holds it.
 */
class SyncMergeTest {

    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final String TIE = "zz-sync-tie";

    @TempDir Path dir;

    private SyncMachines machines;
    private Machine a;
    private Machine b;

    @BeforeEach
    void openMachines() throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        a = machines.clone("a");
        b = machines.clone("b");
    }

    @AfterEach
    void closeMachines() {
        machines.close();
    }

    /** A closes a seed fact; B still holds the seed version, which is older and must not win. */
    @Test
    void both_machines_end_with_every_record_and_the_newer_version_of_each() {
        String seedOs = onlyId(a.graph.openFacts("ornek-app-01", "os", "default"));
        a.graph.write(request(Source.USER, "zz-sync-a", T1, fact("os", "rhel 9", PROD)));
        a.graph.write(
                untyped(Source.USER, "ornek-app-01", T1, fact("os", "Rocky Linux 10", "default")));
        b.graph.write(request(Source.USER, "zz-sync-b", T1, fact("os", "rhel 9", PROD)));

        syncAThenBThenA();

        Snapshot exported = a.export();
        assertThat(b.export()).isEqualTo(exported);
        assertThat(nodeIds(exported))
                .contains("zz-sync-a", "zz-sync-b")
                .containsAll(nodeIds(SnapshotRepos.seed()));
        assertThat(edgeIds(exported)).containsAll(edgeIds(SnapshotRepos.seed()));
        assertThat(b.graph.openFacts("ornek-app-01", "os", "default"))
                .extracting(f -> f.get("value"))
                .containsExactly("Rocky Linux 10");
        assertThat(b.graph.facts("ornek-app-01"))
                .filteredOn(f -> seedOs.equals(f.get("id")))
                .singleElement()
                .satisfies(f -> assertThat(f).containsKey("valid_to"));
        assertThat(
                        machines.remoteFile(SnapshotFormat.NODES_FILE)
                                + machines.remoteFile(SnapshotFormat.EDGES_FILE))
                .isEqualTo(machines.canonical(exported));
    }

    /** Decision 41: "zz tie from b" is the larger canonical row, whichever machine merges. */
    @Test
    void an_equal_updated_at_picks_the_same_winner_everywhere_and_opens_one_conflict_signal() {
        a.graph.writes.importRecords(new Snapshot(List.of(tieNode("zz tie from a")), List.of()));
        b.graph.writes.importRecords(new Snapshot(List.of(tieNode("zz tie from b")), List.of()));

        a.sync();
        SyncResult atB = b.sync();
        a.sync();

        for (Machine machine : List.of(a, b)) {
            assertThat(node(machine.export(), TIE).props())
                    .containsEntry("summary", "zz tie from b");
        }
        assertThat(a.export()).isEqualTo(b.export());
        assertThat(atB.conflicts()).isEqualTo(1);
        assertThat(b.conflictSignalsAbout(TIE)).hasSize(1);
        assertThat(a.conflictSignalsAbout(TIE)).hasSizeLessThanOrEqualTo(1);
        assertThat(machines.remoteHistory()).as("the loser stays in git").contains("zz tie from a");

        syncAThenBThenA();

        assertThat(b.conflictSignalsAbout(TIE)).as("no second signal").hasSize(1);
        assertThat(a.conflictSignalsAbout(TIE)).hasSizeLessThanOrEqualTo(1);
    }

    /** Decision 42: B still holds the batch A reverted; sync carries the revert, not the batch. */
    @Test
    void a_batch_reverted_on_one_machine_does_not_come_back_from_the_other() {
        String batch = "zz-sync-revert-batch";
        a.graph.write(
                inBatch(Source.USER, "zz-sync-revert", T1, batch, fact("os", "rhel 9", PROD)));
        a.sync();
        b.sync();
        assertThat(nodeIds(b.export())).as("precondition").contains("zz-sync-revert");

        a.graph.writes.revert(batch);
        syncAThenBThenA();

        Snapshot exported = a.export();
        assertThat(nodeIds(exported)).doesNotContain("zz-sync-revert");
        assertThat(b.export()).isEqualTo(exported);
        assertThat(stampedWith(exported, batch)).isEmpty();
        // The quoted id: the marker row's id, the batch name, starts with the entity id.
        assertThat(machines.remoteFile(SnapshotFormat.NODES_FILE))
                .doesNotContain("\"zz-sync-revert\"");
        assertThat(node(exported, batch).labels()).contains("RevertedBatch");
        assertThat(node(exported, batch).props()).containsKey("at");
    }

    private void syncAThenBThenA() {
        a.sync();
        b.sync();
        a.sync();
    }

    private static Node tieNode(String summary) {
        return new Node(
                TIE,
                List.of("Entity", "Server"),
                Map.of(
                        "status",
                        "current",
                        "summary",
                        summary,
                        "updated_at",
                        "2026-11-02T09:00:00Z"));
    }

    private static Node node(Snapshot snapshot, String id) {
        return snapshot.nodes().stream()
                .filter(node -> id.equals(node.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id));
    }

    /** Every row of {@code snapshot} carrying {@code batch}, the tombstone itself left out. */
    private static List<String> stampedWith(Snapshot snapshot, String batch) {
        return Stream.concat(
                        snapshot.nodes().stream()
                                .filter(n -> !n.labels().contains("RevertedBatch"))
                                .filter(n -> batch.equals(n.props().get("batch")))
                                .map(Node::id),
                        snapshot.edges().stream()
                                .filter(e -> batch.equals(e.props().get("batch")))
                                .map(Edge::id))
                .toList();
    }

    private static String onlyId(List<Map<String, Object>> facts) {
        assertThat(facts).as("precondition").hasSize(1);
        return (String) facts.getFirst().get("id");
    }
}
