package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relationTo;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ADR-0022 decision 31 (d): one write holds at most 50 facts and 50 relations; more is rejected
 * with an error and a hint and writes nothing. The two limits are separate. Checked in
 * WriteService, so MCP and REST share it.
 */
class WriteSizeLimitTest {

    private static final int LIMIT = 50;
    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String APP = "zz-app";
    private static final String DB = "zz-db";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(request(Source.USER, DB, T1, fact("os", "rhel 9", PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    // role is a text key, so every distinct value is one more open fact.
    private static List<FactInput> facts(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> fact("role", "zz role " + i, PROD))
                .toList();
    }

    // One target; a distinct env per relation keeps every one a new edge.
    private static List<RelationInput> relations(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> relationTo("depends_on", DB, "zz-env-" + i))
                .toList();
    }

    @Test
    void fifty_facts_and_fifty_relations_in_one_write_are_written() {
        WriteResult result =
                graph.write(request(Source.USER, APP, T2, facts(LIMIT), relations(LIMIT)));

        assertThat(result.added()).hasSize(LIMIT);
        assertThat(
                        graph.rows(
                                "MATCH (:Entity {id: $app})-[r:DEPENDS_ON]->(:Entity {id: $db})"
                                        + " RETURN count(r) AS n",
                                Map.of("app", APP, "db", DB)))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("n", (long) LIMIT));
    }

    @ParameterizedTest(name = "{0} facts, {1} relations")
    @CsvSource({"51, 0", "0, 51", "51, 51", "51, 1", "1, 51"})
    void a_write_over_fifty_facts_or_fifty_relations_is_rejected_and_writes_nothing(
            int factCount, int relationCount) {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                APP,
                                                T2,
                                                facts(factCount),
                                                relations(relationCount))));

        assertThat(rejected).as("over the limit").isNotNull();
        assertThat(rejected.getMessage()).isNotBlank();
        assertThat(rejected.hint()).isNotBlank();
        assertThat(rejected.getMessage() + rejected.hint()).doesNotContain("zz role");
        assertThat(graph.state()).isEqualTo(before);
    }
}
