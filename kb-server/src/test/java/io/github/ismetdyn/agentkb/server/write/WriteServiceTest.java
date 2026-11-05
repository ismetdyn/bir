package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.GraphRows.props;
import static io.github.ismetdyn.agentkb.server.write.WriteHarness.ULID;
import static io.github.ismetdyn.agentkb.server.write.WriteHarness.UPDATED_AT;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.time;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * WriteService.write applies WritePolicy (SPEC §7 rules 1-3, ADR-0022 decisions 22-23) to the
 * graph: a fact never changes in place, every change is a new fact linked by SUPERSEDES, and
 * everything a write opens carries its batch, while a refresh writes no batch (decision 10).
 * Expected cells come from the SPEC, not from the policy code.
 */
class WriteServiceTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-10T09:00:00Z");
    private static final String HOST = "zz-host";
    private static final String HOSTNAME = "hostname";
    private static final String VALUE = "zz-host.ornek.example";
    private static final String OTHER_VALUE = "zz-host-2.ornek.example";
    private static final String SUPERSEDED =
            "MATCH (n:Fact)-[r:SUPERSEDES]->(o:Fact) RETURN n.id AS new, o.id AS old,"
                    + " r.id AS id, r.batch AS batch";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @Test
    void a_first_write_opens_entity_fact_and_edge_in_the_seed_form_with_its_batch() {
        WriteResult result =
                graph.write(request(Source.USER, HOST, T1, fact(HOSTNAME, VALUE, PROD)));

        assertThat(result.batch()).isNotBlank();
        Map<String, Object> entity =
                graph.rows(
                                "MATCH (e:Entity:Server {id: $id}) RETURN properties(e) AS e",
                                Map.of("id", HOST))
                        .getFirst();
        assertThat(props(entity.get("e")))
                .containsEntry("status", "current")
                .containsEntry("batch", result.batch())
                .containsKey(UPDATED_AT);
        Map<String, Object> written = graph.facts(HOST).getFirst();
        assertThat((String) written.get("id")).matches(ULID);
        assertThat(written)
                .containsEntry("key", HOSTNAME)
                .containsEntry("value", VALUE)
                .containsEntry("env", PROD)
                .containsEntry("source", "user")
                .containsEntry("status", "ok")
                .containsEntry("valid_from", time(T1))
                .containsEntry("observed_at", time(T1))
                .containsEntry("batch", result.batch())
                .containsKey(UPDATED_AT)
                .doesNotContainKey("valid_to");
        Map<String, Object> edge =
                graph.rows(
                                "MATCH (:Entity {id: $id})-[r:HAS_FACT]->() RETURN properties(r) AS r",
                                Map.of("id", HOST))
                        .getFirst();
        assertThat((String) ((Map<?, ?>) edge.get("r")).get("id")).matches(ULID);
        assertThat(props(edge.get("r"))).containsEntry("batch", result.batch());
    }

    /** Rule 1: the old fact closes and a new one opens with the raised source (decision 22). */
    @Test
    void a_higher_source_on_the_same_value_closes_the_fact_and_opens_a_raised_copy() {
        WriteResult first =
                graph.write(request(Source.AGENT, HOST, T1, fact(HOSTNAME, VALUE, PROD)));

        WriteResult raise =
                graph.write(request(Source.USER, HOST, T2, fact(HOSTNAME, VALUE, PROD)));

        List<Map<String, Object>> open = graph.openFacts(HOST, HOSTNAME, PROD);
        assertThat(open).hasSize(1);
        Map<String, Object> raised = open.getFirst();
        assertThat(raised)
                .containsEntry("value", VALUE)
                .containsEntry("source", "user")
                .containsEntry("status", "ok")
                .containsEntry("valid_from", time(T1))
                .containsEntry("observed_at", time(T2))
                .containsEntry("batch", raise.batch());
        Map<String, Object> closed = closedFact(raised);
        assertThat(closed)
                .containsEntry("source", "agent")
                .containsEntry("status", "unverified")
                .containsEntry("valid_from", time(T1))
                .containsEntry("observed_at", time(T1))
                .containsEntry("valid_to", time(T2))
                .containsEntry("batch", first.batch());
        assertSupersedes(raised, closed, raise.batch());
    }

    /** Rule 1: a lower or equal source only moves observed_at; no new record, no batch. */
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"USER, USER", "USER, AGENT", "SCAN, SCAN", "SCAN, AGENT", "USER_STATED, SCAN"})
    void a_lower_or_equal_source_on_the_same_value_only_refreshes_observed_at(
            Source existing, Source incoming) {
        WriteResult first = graph.write(request(existing, HOST, T1, fact(HOSTNAME, VALUE, PROD)));
        Map<String, Object> before = graph.facts(HOST).getFirst();

        WriteResult refresh = graph.write(request(incoming, HOST, T2, fact(HOSTNAME, VALUE, PROD)));

        List<Map<String, Object>> facts = graph.facts(HOST);
        assertThat(facts).hasSize(1);
        Map<String, Object> after = facts.getFirst();
        assertThat(after.get("observed_at")).isEqualTo(time(T2));
        assertThat(withoutRefreshedFields(after)).isEqualTo(withoutRefreshedFields(before));
        assertThat(after.get("batch")).isEqualTo(first.batch());
        assertThat(graph.rows("MATCH ()-[r:SUPERSEDES]->() RETURN r", Map.of())).isEmpty();
        assertThat(graph.state().toString()).doesNotContain(refresh.batch());
    }

    /** Rule 2: the old value's valid_to is the new value's valid_from. */
    @Test
    void a_different_value_from_an_equal_source_closes_the_old_fact_and_adds_a_new_one() {
        graph.write(request(Source.USER, HOST, T1, fact(HOSTNAME, VALUE, PROD)));

        WriteResult change =
                graph.write(request(Source.USER, HOST, T2, fact(HOSTNAME, OTHER_VALUE, PROD)));

        Map<String, Object> current = graph.openFacts(HOST, HOSTNAME, PROD).getFirst();
        assertThat(graph.openFacts(HOST, HOSTNAME, PROD)).hasSize(1);
        assertThat(current)
                .containsEntry("value", OTHER_VALUE)
                .containsEntry("valid_from", time(T2))
                .containsEntry("batch", change.batch());
        Map<String, Object> old = closedFact(current);
        assertThat(old).containsEntry("value", VALUE).containsEntry("valid_to", time(T2));
        assertSupersedes(current, old, change.batch());
    }

    /**
     * Decision 23: the waiting fact and the old current fact both close; one new current fact with
     * the waiting value and the waiting valid_from supersedes both.
     */
    @Test
    void writing_a_waiting_value_from_a_source_as_trusted_as_the_current_one_resolves_it() {
        graph.write(request(Source.USER_STATED, HOST, T1, fact(HOSTNAME, VALUE, PROD)));
        WriteResult conflict =
                graph.write(request(Source.AGENT, HOST, T2, fact(HOSTNAME, OTHER_VALUE, PROD)));
        assertThat(conflict.conflicts()).as("precondition: the agent value waits").hasSize(1);

        WriteResult resolve =
                graph.write(
                        request(Source.USER_STATED, HOST, T3, fact(HOSTNAME, OTHER_VALUE, PROD)));

        List<Map<String, Object>> open = graph.openFacts(HOST, HOSTNAME, PROD);
        assertThat(open).hasSize(1);
        Map<String, Object> resolved = open.getFirst();
        assertThat(resolved)
                .containsEntry("value", OTHER_VALUE)
                .containsEntry("source", "user_stated")
                .containsEntry("status", "ok")
                .containsEntry("valid_from", time(T2))
                .containsEntry("batch", resolve.batch());
        List<Map<String, Object>> closed =
                graph.facts(HOST).stream().filter(f -> f.containsKey("valid_to")).toList();
        assertThat(closed)
                .extracting(f -> f.get("value"), f -> f.get("valid_to"))
                .containsExactlyInAnyOrder(tuple(VALUE, time(T3)), tuple(OTHER_VALUE, time(T3)));
        for (Map<String, Object> old : closed) {
            assertSupersedes(resolved, old, resolve.batch());
        }
    }

    private Map<String, Object> closedFact(Map<String, Object> successor) {
        Object oldId =
                graph.rows(SUPERSEDED, Map.of()).stream()
                        .filter(row -> successor.get("id").equals(row.get("new")))
                        .findFirst()
                        .orElseThrow()
                        .get("old");
        return graph.facts(HOST).stream()
                .filter(f -> oldId.equals(f.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private void assertSupersedes(
            Map<String, Object> successor, Map<String, Object> old, String batch) {
        assertThat(graph.rows(SUPERSEDED, Map.of()))
                .filteredOn(row -> successor.get("id").equals(row.get("new")))
                .filteredOn(row -> old.get("id").equals(row.get("old")))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat((String) row.get("id")).matches(ULID);
                            assertThat(row.get("batch")).isEqualTo(batch);
                        });
    }

    private static Map<String, Object> withoutRefreshedFields(Map<String, Object> fact) {
        Map<String, Object> rest = new TreeMap<>(fact);
        rest.remove("observed_at");
        rest.remove(UPDATED_AT);
        return rest;
    }
}
