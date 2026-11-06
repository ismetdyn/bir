package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.DATABASE;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Lead decision CP3 S4: ADR-0025 decision 16 covers relations. A lower source writing a relation
 * the user closed opens no edge; it raises a conflict signal and the write reports it. The user
 * opens it again. The fixture's relation is zz-host DEPENDS_ON zz-db (user).
 */
class GraphEditsRelationTombstoneTest {

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

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private List<Map<String, Object>> openRelations() {
        return fixture.graph.rows(
                "MATCH (:Entity {id: $from})-[r:DEPENDS_ON]->(:Entity {id: $to})"
                        + " WHERE r.valid_to IS NULL RETURN r.id AS id",
                Map.of("from", HOST, "to", DATABASE));
    }

    /**
     * Decision 16 covers relations (lead decision CP3 S4): a lower source writing a relation the
     * user closed opens no edge; it waits as a conflict signal, and the write reports it.
     */
    @ParameterizedTest
    @EnumSource(
            value = Source.class,
            names = {"USER_STATED", "SCAN", "AGENT"})
    void a_lower_source_writing_a_relation_the_user_closed_opens_no_edge(Source source) {
        fixture.edits().closeRelation(fixture.relation);

        WriteResult result =
                fixture.graph.write(relation(source, HOST, now(), "depends_on", DATABASE));

        assertThat(openRelations()).isEmpty();
        assertThat(result.conflicts()).containsExactly(fixture.relation);
        assertThat(
                        fixture.graph.rows(
                                "MATCH (s:Signal {kind: 'conflict'})-[:ABOUT]->(:Entity {id: $id})"
                                        + " WHERE s.key CONTAINS 'DEPENDS_ON' RETURN s.id AS id",
                                Map.of("id", HOST)))
                .hasSize(1);
    }

    @Test
    void the_user_writing_a_relation_they_closed_opens_it_again() {
        fixture.edits().closeRelation(fixture.relation);

        WriteResult result =
                fixture.graph.write(relation(Source.USER, HOST, now(), "depends_on", DATABASE));

        assertThat(result.conflicts()).isEmpty();
        assertThat(openRelations()).singleElement().isNotEqualTo(Map.of("id", fixture.relation));
    }
}
