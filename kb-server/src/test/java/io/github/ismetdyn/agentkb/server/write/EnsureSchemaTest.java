package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;

/**
 * GraphMaintenance.ensureSchema: the schema does not depend on what a snapshot happened to hold
 * (ARCHITECTURE §4), and the fulltext index follows its definition in code.
 */
class EnsureSchemaTest {

    private static final String INDEX = "kb_fulltext";

    @TempDir Path dataDir;

    private EmbeddedNeo4jStore store;
    private WriteService writeService;

    @BeforeEach
    void openStore() {
        store = new EmbeddedNeo4jStore(dataDir);
        // No repo: ensuring the schema needs no schema files.
        writeService = new WriteService(store, new RepoSchema(""));
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void an_empty_graph_gets_the_fulltext_index_online() {
        writeService.maintenance().ensureSchema();

        Map<String, Object> index = fulltextIndex();
        assertThat(index.get("state")).isEqualTo("ONLINE");
        assertThat(analyzerOf(index)).isEqualTo("standard-folding");
    }

    @Test
    void an_empty_graph_gets_id_constraints_for_labels_and_edges_no_snapshot_held() {
        writeService.maintenance().ensureSchema();

        assertThat(constrained("NODE"))
                .contains("Entity", "Fact", "Decision", "Lesson", "Secret", "Signal", "Proposal");
        assertThat(constrained("RELATIONSHIP")).contains("HAS_FACT", "SUPERSEDES", "ABOUT");
    }

    @Test
    void a_fulltext_index_with_an_old_definition_is_rebuilt_to_the_current_one() {
        db().executeTransactionally(
                        "CREATE FULLTEXT INDEX kb_fulltext FOR (n:Entity) ON EACH [n.summary]"
                                + " OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard'}}");

        writeService.maintenance().ensureSchema();

        Map<String, Object> index = fulltextIndex();
        assertThat(analyzerOf(index)).isEqualTo("standard-folding");
        assertThat(index.get("labelsOrTypes")).asInstanceOf(LIST).contains("Fact", "Signal");
        assertThat(index.get("properties")).asInstanceOf(LIST).contains("value", "payload");
    }

    @Test
    void a_current_fulltext_index_is_kept_and_not_rebuilt_on_every_start() {
        writeService.maintenance().ensureSchema();
        Object firstId = fulltextIndex().get("id");

        writeService.maintenance().ensureSchema();

        assertThat(fulltextIndex().get("id")).isEqualTo(firstId);
    }

    private GraphDatabaseService db() {
        return store.database();
    }

    private Map<String, Object> fulltextIndex() {
        List<Map<String, Object>> rows =
                db().executeTransactionally(
                                "SHOW FULLTEXT INDEXES YIELD id, name, state, labelsOrTypes, properties,"
                                        + " options WHERE name = $name",
                                Map.of("name", INDEX),
                                result -> result.stream().toList());
        assertThat(rows).as("fulltext index " + INDEX).hasSize(1);
        return rows.getFirst();
    }

    private static Object analyzerOf(Map<String, Object> index) {
        return ((Map<?, ?>) ((Map<?, ?>) index.get("options")).get("indexConfig"))
                .get("fulltext.analyzer");
    }

    private Set<String> constrained(String entityType) {
        return db().executeTransactionally(
                        "SHOW CONSTRAINTS YIELD type, entityType, labelsOrTypes, properties"
                                + " WHERE entityType = $entityType AND properties = ['id']",
                        Map.of("entityType", entityType),
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
