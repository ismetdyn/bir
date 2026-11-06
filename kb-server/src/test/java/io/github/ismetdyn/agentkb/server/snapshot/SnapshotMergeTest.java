package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Lead decision CP3 J6 for the record merge of {@code kb sync}: machine A's user closed a record
 * (closed_batch) while machine B's agent saw it again (a later observed_at, so a later updated_at).
 * The close wins on both machines, like remove-wins in a CRDT; otherwise the sync would reopen what
 * the user closed (ADR-0025 decision 16). A close whose batch was reverted is no close.
 */
class SnapshotMergeTest {

    private static final String CLOSED_AT = "2026-11-01T09:00:00Z";
    private static final String SEEN_LATER = "2026-11-01T10:00:00Z";
    private static final String REVERTED_AT = "2026-11-01T11:00:00Z";
    private static final String CLOSE = "zz-close-batch";

    private static Map<String, Object> closed() {
        return Map.of(
                "value", "rhel 9",
                "valid_to", CLOSED_AT,
                "closed_batch", CLOSE,
                "observed_at", CLOSED_AT,
                "updated_at", CLOSED_AT);
    }

    private static Map<String, Object> seenAgain() {
        return Map.of("value", "rhel 9", "observed_at", SEEN_LATER, "updated_at", SEEN_LATER);
    }

    private static Snapshot fact(Map<String, Object> props, Node... more) {
        return new Snapshot(
                Stream.concat(Stream.of(new Node("zz-f", List.of("Fact"), props)), Stream.of(more))
                        .toList(),
                List.of());
    }

    private static Snapshot relation(Map<String, Object> props) {
        return new Snapshot(
                List.of(
                        new Node("zz-a", List.of("Entity"), Map.of()),
                        new Node("zz-b", List.of("Entity"), Map.of())),
                List.of(new Edge("zz-r", "DEPENDS_ON", "zz-a", "zz-b", props)));
    }

    private static Map<String, Object> nodeProps(Snapshot snapshot, String id) {
        return snapshot.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow()
                .props();
    }

    @Test
    void a_user_close_wins_over_a_later_open_version_of_the_fact_on_both_sides() {
        Snapshot a = fact(closed());
        Snapshot b = fact(seenAgain());

        assertThat(nodeProps(SnapshotMerge.merge(a, b).snapshot(), "zz-f")).isEqualTo(closed());
        assertThat(nodeProps(SnapshotMerge.merge(b, a).snapshot(), "zz-f")).isEqualTo(closed());
    }

    @Test
    void a_user_close_wins_over_a_later_open_version_of_the_relation_on_both_sides() {
        Snapshot a = relation(closed());
        Snapshot b = relation(seenAgain());

        assertThat(SnapshotMerge.merge(a, b).snapshot().edges().getFirst().props())
                .isEqualTo(closed());
        assertThat(SnapshotMerge.merge(b, a).snapshot().edges().getFirst().props())
                .isEqualTo(closed());
    }

    /** kb revert of the close reopened the fact; the old closed copy must not close it again. */
    @Test
    void a_close_whose_batch_was_reverted_does_not_win() {
        Map<String, Object> reopened =
                Map.of("value", "rhel 9", "observed_at", CLOSED_AT, "updated_at", REVERTED_AT);
        Node marker =
                new Node(
                        CLOSE,
                        List.of(SnapshotMerge.REVERTED_BATCH),
                        Map.of("at", REVERTED_AT, "updated_at", REVERTED_AT));
        Snapshot a = fact(reopened, marker);
        Snapshot b = fact(closed());

        assertThat(nodeProps(SnapshotMerge.merge(a, b).snapshot(), "zz-f")).isEqualTo(reopened);
        assertThat(nodeProps(SnapshotMerge.merge(b, a).snapshot(), "zz-f")).isEqualTo(reopened);
    }
}
