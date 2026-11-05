package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteHarness.ULID;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC §6.2: relation types come from schema/relations.yaml and synonyms are translated while
 * writing (invariant 7). A vocabulary type is data, so it has no id constraint until a write needs
 * one: GraphSchema.ensure runs before the first edge of a new type (todo G7, lead decision).
 */
class RelationWriteTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String PROJECT = "zz-app";
    private static final String DATABASE = "zz-db";
    private static final String EDGE =
            "MATCH (:Entity {id: $from})-[r]->(:Entity {id: $to}) RETURN type(r) AS type,"
                    + " properties(r) AS props";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(request(Source.USER, PROJECT, T1, fact("os", "rhel 9", PROD)));
        graph.write(request(Source.USER, DATABASE, T1, fact("engine", "postgresql", PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @Test
    void the_first_edge_of_a_vocabulary_type_gets_its_id_constraint_first() {
        assertThat(constrainedEdgeTypes()).as("precondition").doesNotContain("DEPENDS_ON");

        graph.write(relation(Source.USER, PROJECT, T2, "depends_on", DATABASE));

        assertThat(constrainedEdgeTypes()).contains("DEPENDS_ON");
    }

    @ParameterizedTest(name = "{0} is written as DEPENDS_ON")
    @ValueSource(strings = {"depends_on", "DEPENDS_ON", "uses", "connects_to"})
    void a_relation_type_or_its_synonym_is_written_in_its_graph_form(String type) {
        WriteResult result = graph.write(relation(Source.AGENT, PROJECT, T2, type, DATABASE));

        List<Map<String, Object>> edges = graph.rows(EDGE, Map.of("from", PROJECT, "to", DATABASE));
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().get("type")).isEqualTo("DEPENDS_ON");
        Map<String, Object> props = GraphRows.props(edges.getFirst().get("props"));
        assertThat((String) props.get("id")).matches(ULID);
        assertThat(props)
                .containsEntry("source", "agent")
                .containsEntry("valid_from", time(T2))
                .containsEntry("observed_at", time(T2))
                .containsEntry("batch", result.batch())
                .doesNotContainKey("valid_to");
    }

    private Set<String> constrainedEdgeTypes() {
        return graph.store
                .database()
                .executeTransactionally(
                        "SHOW CONSTRAINTS YIELD entityType, labelsOrTypes, properties"
                                + " WHERE entityType = 'RELATIONSHIP' AND properties = ['id']",
                        Map.of(),
                        result ->
                                result.stream()
                                        .flatMap(
                                                row ->
                                                        ((List<?>) row.get("labelsOrTypes"))
                                                                .stream())
                                        .map(String::valueOf)
                                        .collect(Collectors.toSet()));
    }
}
