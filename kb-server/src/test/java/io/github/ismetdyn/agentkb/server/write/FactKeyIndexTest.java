package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;

/**
 * WriteService.ensureSchema indexes Fact.key, so RequestWrite's address lookup seeks instead of
 * reading every Fact. Fact.value and Entity.aliases stay unindexed: a range index refuses a value
 * above its key limit (~8 kB), and import and the scanner do not cap values (invariant 11).
 */
class FactKeyIndexTest {

    @TempDir Path dataDir;

    private EmbeddedNeo4jStore store;

    @BeforeEach
    void openStore() {
        store = new EmbeddedNeo4jStore(dataDir);
        // No repo: ensuring the schema needs no schema files.
        new WriteService(store, new RepoSchema("")).ensureSchema();
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void an_empty_graph_gets_an_online_range_index_on_fact_key() {
        List<String> online =
                db().executeTransactionally(
                                "SHOW RANGE INDEXES YIELD labelsOrTypes, properties, state"
                                        + " WHERE state = 'ONLINE'"
                                        + " RETURN labelsOrTypes[0] + '.' + properties[0] AS on",
                                Map.of(),
                                result ->
                                        result.stream()
                                                .map(row -> (String) row.get("on"))
                                                .toList());
        assertThat(online).contains("Fact.key");
    }

    @Test
    void a_fact_value_or_aliases_beyond_the_range_index_key_limit_is_still_written() {
        String large = "x".repeat(40_000);

        db().executeTransactionally(
                        "CREATE (:Fact {id: 'f1', key: 'k', value: $large})"
                                + " CREATE (:Entity {id: 'e1', aliases: [$large]})",
                        Map.of("large", large));

        Object size =
                db().executeTransactionally(
                                "MATCH (f:Fact {id: 'f1'}) RETURN size(f.value) AS size",
                                Map.of(),
                                result -> result.next().get("size"));
        assertThat(size).isEqualTo(40_000L);
    }

    private GraphDatabaseService db() {
        return store.database();
    }
}
