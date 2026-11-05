package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.GraphRows.props;
import static io.github.ismetdyn.agentkb.server.write.WriteHarness.ULID;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
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
 * SPEC §7 rule 3 and ADR-0022 decision 7: a lower source with a different value is written with
 * status conflict next to the current value, which stays, and opens a conflict Signal ABOUT the
 * entity. One kind + key is one node (SPEC §6.1): a repeat raises count and last_at. Signals are
 * local and never reach the snapshot (invariant 8).
 */
class ConflictSignalTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-10T09:00:00Z");
    private static final String HOST = "zz-host";
    private static final String HOSTNAME = "hostname";
    private static final String KEPT = "zz-host.ornek.example";
    private static final String DISPUTED = "zz-host-other.ornek.example";
    private static final String SIGNALS =
            "MATCH (s:Signal {kind: 'conflict'}) OPTIONAL MATCH (s)-[a:ABOUT]->(x)"
                    + " RETURN properties(s) AS s, collect(x.id) AS about, count(a) AS edges";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(request(Source.USER, HOST, T1, fact(HOSTNAME, KEPT, PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @Test
    void a_lower_source_with_another_value_waits_and_opens_one_signal_about_the_entity() {
        Map<String, Object> current = graph.facts(HOST).getFirst();

        WriteResult result =
                graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, PROD)));

        List<Map<String, Object>> open = graph.openFacts(HOST, HOSTNAME, PROD);
        assertThat(open).hasSize(2).contains(current);
        Map<String, Object> waiting =
                open.stream().filter(f -> !f.equals(current)).findFirst().orElseThrow();
        assertThat(waiting)
                .containsEntry("value", DISPUTED)
                .containsEntry("status", "conflict")
                .containsEntry("source", "agent")
                .containsEntry("batch", result.batch());
        assertThat(result.conflicts()).containsExactly((String) waiting.get("id"));

        Map<String, Object> signal = onlySignal();
        Map<String, Object> props = props(signal.get("s"));
        assertThat((String) props.get("id")).matches(ULID);
        assertThat(props)
                .containsEntry("kind", "conflict")
                .containsEntry("status", "open")
                .containsEntry("count", 1L)
                .containsEntry("at", time(T2))
                .containsEntry("last_at", time(T2))
                .containsKeys("key", "payload");
        assertThat(signal.get("about")).isEqualTo(List.of(HOST));
        assertThat(signal.get("edges")).isEqualTo(1L);
    }

    @Test
    void the_same_conflict_again_raises_count_and_last_at_on_the_same_signal() {
        graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, PROD)));
        Object key = ((Map<?, ?>) onlySignal().get("s")).get("key");

        graph.write(request(Source.AGENT, HOST, T3, fact(HOSTNAME, DISPUTED, PROD)));

        Map<String, Object> props = props(onlySignal().get("s"));
        assertThat(props)
                .containsEntry("key", key)
                .containsEntry("count", 2L)
                .containsEntry("at", time(T2))
                .containsEntry("last_at", time(T3));
        assertThat(onlySignal().get("edges")).as("still one ABOUT edge").isEqualTo(1L);
        assertThat(graph.openFacts(HOST, HOSTNAME, PROD))
                .as("the waiting value is refreshed, not written twice")
                .hasSize(2);
    }

    /**
     * The key is entity + key + env: another disputed value of the same slot is the same signal.
     */
    @Test
    void another_disputed_value_of_the_same_key_and_env_is_the_same_signal() {
        graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, PROD)));

        graph.write(
                request(Source.AGENT, HOST, T3, fact(HOSTNAME, "zz-third.ornek.example", PROD)));

        assertThat(props(onlySignal().get("s"))).containsEntry("count", 2L);
    }

    @Test
    void a_conflict_in_another_env_or_on_another_entity_is_another_signal() {
        graph.write(request(Source.USER, HOST, T1, fact(HOSTNAME, KEPT, "test")));
        graph.write(request(Source.USER, "zz-other", T1, fact(HOSTNAME, KEPT, PROD)));

        graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, PROD)));
        graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, "test")));
        graph.write(request(Source.AGENT, "zz-other", T2, fact(HOSTNAME, DISPUTED, PROD)));

        List<Map<String, Object>> signals = graph.rows(SIGNALS, Map.of());
        assertThat(signals).hasSize(3);
        assertThat(signals)
                .extracting(row -> ((Map<?, ?>) row.get("s")).get("key"))
                .doesNotHaveDuplicates();
    }

    @Test
    void the_snapshot_holds_the_waiting_fact_but_no_signal_and_no_edge_to_one() {
        graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, DISPUTED, PROD)));
        Object signalId = ((Map<?, ?>) onlySignal().get("s")).get("id");

        Snapshot snapshot = new SnapshotExporter(graph.store).export();

        assertThat(snapshot.nodes()).noneMatch(node -> node.labels().contains("Signal"));
        assertThat(snapshot.nodes()).noneMatch(node -> node.id().equals(signalId));
        assertThat(snapshot.edges())
                .noneMatch(edge -> edge.from().equals(signalId) || edge.to().equals(signalId));
        assertThat(snapshot.nodes()).anyMatch(node -> DISPUTED.equals(node.props().get("value")));
    }

    private Map<String, Object> onlySignal() {
        List<Map<String, Object>> signals = graph.rows(SIGNALS, Map.of());
        assertThat(signals).as("conflict signals").hasSize(1);
        return signals.getFirst();
    }
}
