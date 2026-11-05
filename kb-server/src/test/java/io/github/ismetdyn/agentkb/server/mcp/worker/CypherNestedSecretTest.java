package io.github.ismetdyn.agentkb.server.mcp.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant 2 for secret values that hold each other: masking a shorter one first would leave the
 * rest of a longer one readable in a kb_cypher answer. Eight nested values, because the filter's
 * set of values has no fixed order and two would expose the fault only every other run. The answer
 * is the longest value written into the query itself, the case the output filter exists for.
 */
class CypherNestedSecretTest extends RunningServerTestBase {

    private static final String MARKER = "9a3e60c2d81b47f5";
    private static final String TAIL = "-tailq";
    private static final String ID_PREFIX = "kbtest-nested-secret-";
    private static final int NESTED_SECRETS = 8;

    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void a_secret_value_inside_a_longer_one_leaves_no_part_of_the_longer_one() throws Exception {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder("kbtest-FAKE-" + MARKER);
        for (int i = 0; i < NESTED_SECRETS; i++) {
            values.add(value.toString());
            value.append(TAIL).append(i);
        }
        store.database()
                .executeTransactionally(
                        "UNWIND range(0, size($values) - 1) AS i"
                                + " CREATE (:Secret {id: $prefix + i, secret_value: $values[i]})",
                        Map.of("values", values, "prefix", ID_PREFIX));

        String text =
                McpClient.open(port)
                        .text(
                                "kb_cypher",
                                Map.of("query", "RETURN '" + values.getLast() + "' AS x"));

        assertThat(MarkdownTable.cells(text)).as("header and one row: %s", text).hasSize(2);
        assertThat(text).doesNotContain(MARKER).doesNotContain(TAIL);
    }

    // The context is shared; other tests count the seed's three Secret nodes.
    @AfterEach
    void removeNestedSecrets() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret) WHERE s.id STARTS WITH $prefix DELETE s",
                        Map.of("prefix", ID_PREFIX));
    }
}
