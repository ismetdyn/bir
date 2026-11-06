package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos.nodeIds;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.inBatch;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.untyped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.CheckReport.Problem;
import io.github.ismetdyn.agentkb.server.snapshot.CheckService;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb sync} when two machines act on the same rows before either sees the other's work
 * (ADR-0022 decisions 42-43): their settles must merge into one row, and a revert one of them did
 * must not lose the other's later record. A and B start from the seed.
 */
class SyncUnseenWorkTest {

    private static final String SLOT_FACT_A = "01KBTESTSYNCSLOTFACTA00001";
    private static final String SLOT_FACT_B = "01KBTESTSYNCSLOTFACTB00001";
    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");

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

    /**
     * A and B both hold the same two equal-trust values of one single-valued slot from a third
     * machine and settle them on their own: B merged and settled in a round whose push lost the
     * race to A's. Both open the same SUPERSEDES row, so the merged graph closes the older value
     * once.
     */
    @Test
    void two_machines_that_settle_the_same_slot_on_their_own_merge_into_one_supersedes_edge() {
        Snapshot older =
                SyncConsistencyTest.slotWith("A", "zz-os-a", "user", "2026-11-02T09:00:00Z");
        Snapshot newer =
                SyncConsistencyTest.slotWith("B", "zz-os-b", "user", "2026-11-03T09:00:00Z");
        Snapshot third =
                new Snapshot(
                        List.of(older.nodes().get(0), older.nodes().get(1), newer.nodes().get(1)),
                        List.of(older.edges().getFirst(), newer.edges().getFirst()));
        a.graph.writes.importRecords(third);
        b.graph.writes.importRecords(third);
        b.graph.writes.maintenance.mergeSnapshot(b.export(), third);

        a.sync();
        b.sync();
        a.sync();

        assertThat(a.export()).isEqualTo(b.export());
        assertThat(a.export().edges())
                .filteredOn(
                        edge -> "SUPERSEDES".equals(edge.type()) && SLOT_FACT_A.equals(edge.to()))
                .singleElement()
                .satisfies(edge -> assertThat(edge.from()).isEqualTo(SLOT_FACT_B));
    }

    /**
     * B built a fact on A's batch, A reverted the batch before it saw that fact (so its local "no
     * partial revert" check had nothing to refuse). The merge drops the entity but keeps B's fact
     * (invariant 11), and kb check names the fact no entity holds on both machines.
     */
    @Test
    void a_fact_left_without_its_entity_by_a_synced_revert_stays_and_kb_check_names_it() {
        String batch = "zz-sync-built-on-batch";
        a.graph.write(inBatch(Source.USER, "zz-sync-base", T1, batch, fact("os", "rhel 9", PROD)));
        a.sync();
        b.sync();
        b.graph.write(
                untyped(
                        Source.USER,
                        "zz-sync-base",
                        T1.plusSeconds(60),
                        fact("version", "9", PROD)));
        String later =
                (String) b.graph.openFacts("zz-sync-base", "version", PROD).getFirst().get("id");

        a.graph.writes.revert(batch);
        a.sync();
        b.sync();
        a.sync();

        assertThat(b.export()).isEqualTo(a.export());
        assertThat(nodeIds(a.export())).doesNotContain("zz-sync-base").contains(later);
        for (Machine machine : List.of(a, b)) {
            assertThat(new CheckService(machine.graph.store, "").check().problems())
                    .extracting(Problem::kind, Problem::id)
                    .contains(tuple("orphan-fact", later));
        }
    }
}
