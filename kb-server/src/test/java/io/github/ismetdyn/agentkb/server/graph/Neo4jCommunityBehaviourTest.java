package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;

/**
 * Facts about the Neo4j Community edition the plan relied on without measuring (plan.md
 * "Doğrulanmamış varsayımlar", "Riskler"). Each test pins what the pinned version really does, so
 * an upgrade that changes it fails here first.
 */
class Neo4jCommunityBehaviourTest {

    private static final String SENTINEL = "SENTINEL-query-log-5c81";

    @TempDir Path dataDir;

    /**
     * Relationship property uniqueness works per relationship type only. ARCHITECTURE §4 ("each
     * label id unique") therefore needs one constraint per relationship type, and a duplicate id
     * across two types is not caught by the database.
     */
    @Test
    void relationship_id_uniqueness_holds_within_one_type_but_not_across_types() {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir)) {
            GraphDatabaseService db = store.database();
            assertThatCode(
                            () ->
                                    db.executeTransactionally(
                                            "CREATE CONSTRAINT has_fact_id FOR ()-[r:HAS_FACT]-() REQUIRE r.id IS UNIQUE"))
                    .doesNotThrowAnyException();
            db.executeTransactionally(
                    "CREATE CONSTRAINT from_id FOR ()-[r:FROM]-() REQUIRE r.id IS UNIQUE");

            assertThatThrownBy(
                            () ->
                                    db.executeTransactionally(
                                            "CREATE (:N)-[:HAS_FACT {id: 'same'}]->(:N), (:N)-[:HAS_FACT {id: 'same'}]->(:N)"))
                    .isInstanceOf(QueryExecutionException.class)
                    .hasMessageContaining("already exists");
            assertThatCode(
                            () ->
                                    db.executeTransactionally(
                                            "CREATE (:N)-[:HAS_FACT {id: 'shared'}]->(:N), (:N)-[:FROM {id: 'shared'}]->(:N)"))
                    .as("both types are constrained, yet the same id across them is accepted")
                    .doesNotThrowAnyException();
            assertThatThrownBy(
                            () ->
                                    db.executeTransactionally(
                                            "CREATE CONSTRAINT any_rel_id FOR ()-[r]-() REQUIRE r.id IS UNIQUE"))
                    .as("a constraint over every relationship type is not expressible")
                    .isInstanceOf(QueryExecutionException.class);
        }
    }

    /**
     * plan.md requires the query log off (secret side channel). Query text, parameters and failing
     * queries carrying a sentinel must not reach any file under the data directory.
     */
    @Test
    void no_file_under_the_data_dir_holds_query_text_or_parameters() throws IOException {
        try (EmbeddedNeo4jStore store = new EmbeddedNeo4jStore(dataDir)) {
            runSentinelQueries(store.database());
        }

        assertThat(filesContaining(dataDir, SENTINEL)).isEmpty();
    }

    /**
     * Why the test above cannot go red today: even with query logging switched to VERBOSE and
     * parameter logging on, this edition writes no query text. If an upgrade starts honouring the
     * setting, this fails and EmbeddedNeo4jStore must switch the query log off explicitly.
     */
    @Test
    void community_edition_ignores_verbose_query_logging() throws IOException {
        DatabaseManagementService management =
                new DatabaseManagementServiceBuilder(dataDir.resolve("verbose"))
                        .setConfig(
                                GraphDatabaseSettings.log_queries,
                                GraphDatabaseSettings.LogQueryLevel.VERBOSE)
                        .setConfig(
                                GraphDatabaseSettings.log_queries_parameter_logging_enabled, true)
                        .build();
        try {
            runSentinelQueries(management.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME));
        } finally {
            management.shutdown();
        }

        assertThat(filesContaining(dataDir, SENTINEL)).isEmpty();
    }

    private static void runSentinelQueries(GraphDatabaseService db) {
        db.executeTransactionally(
                "RETURN '" + SENTINEL + "-literal' AS a, $p AS b",
                Map.of("p", SENTINEL + "-parameter"));
        assertThatThrownBy(() -> db.executeTransactionally("RETURN " + SENTINEL + "-broken("))
                .isInstanceOf(QueryExecutionException.class);
    }

    private static List<Path> filesContaining(Path root, String asciiText) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> regular = files.filter(Files::isRegularFile).toList();
            assertThat(regular)
                    .as("the walk found the Neo4j logs")
                    .anyMatch(file -> file.endsWith(Path.of("logs", "query.log")));
            return regular.stream().filter(file -> contains(file, asciiText)).toList();
        }
    }

    // ISO-8859-1 maps every byte to one char, so binary store files decode without loss.
    private static boolean contains(Path file, String asciiText) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                    .contains(asciiText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
