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

/**
 * Lead decision 3: {@code kb revert --batch} finds a batch through an index on the batch property,
 * for nodes and for edges, instead of a scan of every node and edge. WriteService.ensureSchema
 * creates it on every start, like the rest of the fixed schema (EnsureSchemaTest).
 */
class BatchIndexTest {

    @TempDir Path dataDir;

    private EmbeddedNeo4jStore store;

    @BeforeEach
    void openStore() {
        store = new EmbeddedNeo4jStore(dataDir);
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void an_empty_graph_gets_an_online_index_on_batch_for_nodes_and_edges() {
        // No repo: ensuring the schema needs no schema files.
        new WriteService(store, new RepoSchema("")).ensureSchema();

        List<Map<String, Object>> batchIndexes =
                store.database()
                        .executeTransactionally(
                                "SHOW INDEXES YIELD entityType, properties, state"
                                        + " WHERE properties = ['batch']",
                                Map.of(),
                                result -> result.stream().toList());
        assertThat(batchIndexes)
                .extracting(index -> index.get("entityType"))
                .contains("NODE", "RELATIONSHIP");
        assertThat(batchIndexes)
                .allSatisfy(index -> assertThat(index.get("state")).isEqualTo("ONLINE"));
    }
}
