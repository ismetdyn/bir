package io.github.ismetdyn.agentkb.server.mcp.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant 2 for two secret values that overlap: the end of one is the start of the other. Masking
 * one value after the other leaves the second one's remainder readable, whichever goes first; the
 * whole covered run must become one mask. The values differ in length so the longest-first order is
 * fixed and the fault shows on every run.
 */
class CypherOverlappingSecretTest extends RunningServerTestBase {

    private static final String HEAD = "kbtestFAKEhead7c1";
    private static final String OVERLAP = "XYZq8w3";
    private static final String TAIL = "tail4e9FAKEkbtestlonger";
    private static final String FIRST = HEAD + OVERLAP;
    private static final String SECOND = OVERLAP + TAIL;
    private static final String ID_PREFIX = "kbtest-overlap-secret-";

    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void two_overlapping_secret_values_leave_no_character_of_either() throws Exception {
        store.database()
                .executeTransactionally(
                        "UNWIND range(0, size($values) - 1) AS i"
                                + " CREATE (:Secret {id: $prefix + i, secret_value: $values[i]})",
                        Map.of("values", List.of(FIRST, SECOND), "prefix", ID_PREFIX));

        String text =
                McpClient.open(port)
                        .text("kb_cypher", Map.of("query", "RETURN '" + HEAD + SECOND + "' AS x"));

        List<List<String>> cells = MarkdownTable.cells(text);
        assertThat(cells).as("header and one row: %s", text).hasSize(2);
        assertThat(cells.get(1).getFirst())
                .as(text)
                .doesNotContain(HEAD)
                .doesNotContain(OVERLAP)
                .doesNotContain(TAIL)
                .doesNotContainPattern("[a-zA-Z0-9]");
    }

    // The context is shared; other tests count the seed's three Secret nodes.
    @AfterEach
    void removeOverlappingSecrets() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret) WHERE s.id STARTS WITH $prefix DELETE s",
                        Map.of("prefix", ID_PREFIX));
    }
}
