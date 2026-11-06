package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After the merge of {@code kb sync} (ADR-0022 decision 43, CLAUDE.md invariants 2, 8 and 11): two
 * machines that each opened a new value of one single-valued slot keep both values, and the slot is
 * consistent again: the lower trust one waits as a conflict; with equal trust the newer one stays
 * current and a conflict signal opens. Secret values and signals never leave their machine. Each
 * machine imports its rows with fixed times (importRecords keeps updated_at), so "newer" does not
 * depend on the wall clock. A and B start from the seed; sync A, sync B, sync A.
 */
class SyncConsistencyTest {

    private static final String SLOT = "zz-sync-slot";
    private static final String OLDER = "2026-11-02T09:00:00Z";
    private static final String NEWER = "2026-11-03T09:00:00Z";
    private static final String SEED_SECRET = "ornek-lb/prod/panel-admin";
    private static final String VALUE_A = "kbtest-FAKE-sync-a-5d2e8c71";
    private static final String VALUE_B = "kbtest-FAKE-sync-b-9a04f3be";
    private static final String QUESTION = "zz-sync question";
    private static final String ANSWER = "zz-sync answer 3c81";

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

    /** A's user value and B's newer agent value: trust decides, not time. */
    @Test
    void two_new_values_of_one_slot_are_both_kept_and_the_lower_trust_one_becomes_a_conflict() {
        a.graph.writes.maintenance().importRecords(slotWith("A", "zz-os-a", "user", OLDER));
        b.graph.writes.maintenance().importRecords(slotWith("B", "zz-os-b", "agent", NEWER));

        syncAThenBThenA();

        for (Machine machine : List.of(a, b)) {
            assertThat(osValues(machine, false)).containsExactlyInAnyOrder("zz-os-a", "zz-os-b");
            assertThat(osValues(machine, true)).containsExactly("zz-os-a");
            assertThat(machine.graph.facts(SLOT))
                    .filteredOn(f -> "zz-os-b".equals(f.get("value")))
                    .singleElement()
                    .satisfies(
                            f ->
                                    assertThat(f)
                                            .containsEntry("status", "conflict")
                                            .doesNotContainKey("valid_to"));
        }
        assertThat(a.export()).isEqualTo(b.export());
    }

    @Test
    void two_new_values_of_one_slot_with_equal_trust_keep_the_newer_current_and_open_a_signal() {
        a.graph.writes.maintenance().importRecords(slotWith("A", "zz-os-a", "user", OLDER));
        b.graph.writes.maintenance().importRecords(slotWith("B", "zz-os-b", "user", NEWER));

        syncAThenBThenA();

        for (Machine machine : List.of(a, b)) {
            assertThat(osValues(machine, false)).containsExactlyInAnyOrder("zz-os-a", "zz-os-b");
            assertThat(osValues(machine, true)).containsExactly("zz-os-b");
        }
        assertThat(a.export()).isEqualTo(b.export());
        assertThat(b.conflictSignalsAbout(SLOT)).hasSize(1);
    }

    /**
     * Each machine keeps its own value of one Secret and its own signal, even when the other
     * machine's row of that Secret is newer; no git history anywhere holds them.
     */
    @Test
    void secret_values_and_signals_stay_on_their_machine_and_survive_sync() {
        a.graph.writes.putSecret(SEED_SECRET, VALUE_A);
        b.graph.writes.putSecret(SEED_SECRET, VALUE_B);
        a.graph.writes.raiseSignal("qa-capture", QUESTION, ANSWER, Instant.parse(OLDER));
        List<Map<String, Object>> signalOnA = signal(a);
        assertThat(signalOnA).as("precondition").hasSize(1);

        syncAThenBThenA();

        assertThat(secretValue(a)).isEqualTo(VALUE_A);
        assertThat(secretValue(b)).isEqualTo(VALUE_B);
        assertThat(signal(a)).isEqualTo(signalOnA);
        assertThat(signal(b)).isEmpty();
        assertThat(machines.remoteHead()).as("a sync was pushed").isNotEqualTo(machines.seedCommit);
        assertThat(machines.remoteHistory() + a.history() + b.history())
                .contains(SEED_SECRET)
                .doesNotContain(VALUE_A, VALUE_B, QUESTION, ANSWER);
    }

    private void syncAThenBThenA() {
        a.sync();
        b.sync();
        a.sync();
    }

    /** The same entity row on both machines, plus one os@prod fact of this machine. */
    /** Also the rows of SyncUnseenWorkTest. */
    static Snapshot slotWith(String machine, String value, String source, String at) {
        String factId = "01KBTESTSYNCSLOTFACT" + machine + "00001";
        Node entity =
                new Node(
                        SLOT,
                        List.of("Entity", "Server"),
                        Map.of(
                                "status",
                                "current",
                                "summary",
                                "zz slot",
                                "updated_at",
                                "2026-11-01T08:00:00Z"));
        Node fact =
                new Node(
                        factId,
                        List.of("Fact"),
                        Map.of(
                                "env", PROD,
                                "key", "os",
                                "observed_at", at,
                                "source", source,
                                "status", "ok",
                                "updated_at", at,
                                "valid_from", at,
                                "value", value));
        Edge hasFact =
                new Edge(
                        "01KBTESTSYNCSLOTEDGE" + machine + "00001",
                        "HAS_FACT",
                        SLOT,
                        factId,
                        Map.of("updated_at", at));
        return new Snapshot(List.of(entity, fact), List.of(hasFact));
    }

    /** The values of the slot's facts; {@code currentOnly}: open and not waiting as a conflict. */
    private static List<Object> osValues(Machine machine, boolean currentOnly) {
        return machine.graph.facts(SLOT).stream()
                .filter(f -> "os".equals(f.get("key")) && PROD.equals(f.get("env")))
                .filter(
                        f ->
                                !currentOnly
                                        || !f.containsKey("valid_to")
                                                && !"conflict".equals(f.get("status")))
                .map(f -> f.get("value"))
                .toList();
    }

    private static List<Map<String, Object>> signal(Machine machine) {
        return machine.graph.rows(
                "MATCH (s:Signal {key: $key}) RETURN properties(s) AS props",
                Map.of("key", QUESTION));
    }

    private static Object secretValue(Machine machine) {
        return machine.graph
                .rows(
                        "MATCH (s:Secret {id: $id}) RETURN s.secret_value AS v",
                        Map.of("id", SEED_SECRET))
                .getFirst()
                .get("v");
    }
}
