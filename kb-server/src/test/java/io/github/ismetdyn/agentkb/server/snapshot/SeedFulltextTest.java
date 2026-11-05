package io.github.ismetdyn.agentkb.server.snapshot;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormatTest.SEED_SNAPSHOT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.graphdb.GraphDatabaseService;

/**
 * ADR-0018 decision 6 and eval q12: after the seed import the fulltext index uses standard-folding,
 * so "izmir" in any casing finds the dotted capital İ. ornek-yedek-01 has İzmir only in its
 * summary, so only the index can find it. The index is looked up by what it covers, not by name.
 */
class SeedFulltextTest {

    private static final String TURKISH_ENTITY = "ornek-yedek-01";
    private static final int INDEX_AWAIT_SECONDS = 120;

    @TempDir static Path dataDir;

    private static EmbeddedNeo4jStore store;
    private static String index;

    @BeforeAll
    static void importSeedAndFindIndex() throws IOException {
        store = new EmbeddedNeo4jStore(dataDir);
        new WriteService(store, new RepoSchema(""))
                .importRecords(SnapshotFormat.read(SEED_SNAPSHOT));
        index = entitySummaryIndexWithStandardFolding(store.database());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"izmir", "İzmir", "IZMIR", "Izmir"})
    void every_casing_of_izmir_finds_the_entity_with_dotted_capital_i(String query) {
        assertThat(fulltextIds(query)).contains(TURKISH_ENTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ankara", "izmit"})
    void an_unrelated_word_does_not_find_it(String query) {
        assertThat(fulltextIds(query)).doesNotContain(TURKISH_ENTITY);
    }

    private static String entitySummaryIndexWithStandardFolding(GraphDatabaseService db) {
        db.executeTransactionally(
                "CALL db.awaitIndexes($seconds)", Map.of("seconds", INDEX_AWAIT_SECONDS));
        List<Map<String, Object>> indexes =
                db.executeTransactionally(
                        "SHOW FULLTEXT INDEXES YIELD name, labelsOrTypes, properties, options",
                        Map.of(),
                        result -> result.stream().toList());
        Map<String, Object> found =
                indexes.stream()
                        .filter(row -> ((List<?>) row.get("labelsOrTypes")).contains("Entity"))
                        .filter(row -> ((List<?>) row.get("properties")).contains("summary"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no fulltext index on Entity.summary: " + indexes));
        Map<?, ?> config = (Map<?, ?>) ((Map<?, ?>) found.get("options")).get("indexConfig");
        assertThat(config.get("fulltext.analyzer")).isEqualTo("standard-folding");
        return (String) found.get("name");
    }

    private static List<String> fulltextIds(String query) {
        return store.database()
                .executeTransactionally(
                        "CALL db.index.fulltext.queryNodes($index, $query) YIELD node RETURN node.id AS id",
                        Map.of("index", index, "query", query),
                        result -> result.stream().map(row -> (String) row.get("id")).toList());
    }
}
