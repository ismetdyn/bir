package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;

/**
 * ADR-0022 decision 2: write(fn) is reachable only from the write package (ArchUnit). That holds
 * only if read(fn) cannot write, so every write path inside read must fail and change nothing.
 */
class StoreReadWriteTest {

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
    void write_commits_and_read_returns_what_the_function_computes() {
        store.write(tx -> tx.execute("CREATE (:Note {name: 'written'})").resultAsString());

        assertThat(store.read(StoreReadWriteTest::names)).containsExactly("written");
    }

    @Test
    void write_rolls_back_everything_when_the_function_throws() {
        assertThatThrownBy(
                        () ->
                                store.write(
                                        tx -> {
                                            tx.execute("CREATE (:Note {name: 'partial'})");
                                            throw new IllegalStateException("boom");
                                        }))
                .hasMessageContaining("boom");

        assertThat(store.read(StoreReadWriteTest::names)).isEmpty();
    }

    @Test
    void read_rejects_cypher_create_set_and_delete_and_leaves_the_graph_unchanged() {
        store.database().executeTransactionally("CREATE (:Note {name: 'existing'})");

        for (String cypher :
                List.of(
                        "CREATE (:Note {name: 'sneaked'})",
                        "MATCH (n:Note) SET n.name = 'changed'",
                        "MATCH (n:Note) DETACH DELETE n")) {
            assertThatThrownBy(() -> store.read(tx -> tx.execute(cypher).resultAsString()), cypher);
        }

        assertThat(store.read(StoreReadWriteTest::names)).containsExactly("existing");
    }

    @Test
    void read_rejects_a_core_api_write_and_a_schema_change() {
        assertThatThrownBy(
                () -> store.read(tx -> tx.createNode(Label.label("Note")).getElementId()));
        assertThatThrownBy(
                () ->
                        store.read(
                                tx ->
                                        tx.execute("CREATE INDEX sneaked FOR (n:Note) ON (n.name)")
                                                .resultAsString()));

        assertThat(store.read(StoreReadWriteTest::names)).isEmpty();
        long sneakedIndexes =
                store.database()
                        .executeTransactionally(
                                "SHOW INDEXES YIELD name WHERE name = 'sneaked' RETURN count(*) AS c",
                                Map.of(),
                                result -> (Long) result.next().get("c"));
        assertThat(sneakedIndexes).isZero();
    }

    private static List<String> names(Transaction tx) {
        return tx.execute("MATCH (n:Note) RETURN n.name AS name").stream()
                .map(row -> (String) row.get("name"))
                .toList();
    }
}
