package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two lines behind CypherGuard, with the guard bypassed: {@link KnowledgeQueries#cypher} runs
 * the text it is given, as kb_cypher would after a guard miss. It runs in the store's read-only
 * transaction, so a missed write fails and changes nothing (invariant 7), and its rows never carry
 * a secret value, however the query reached it (invariant 2, SPEC §13.5 "sonuçlardan bu alan
 * çıkarılır").
 */
class CypherQueryTest extends RunningServerTestBase {

    private static final String MARKER = "c41d7e0b93fa2658";
    private static final String FAKE_VALUE = "kbtest-FAKE-" + MARKER;
    // The seed's secrets; other server tests (kb_scan) add their own to the shared graph.
    private static final List<String> SEED_SECRET_IDS =
            List.of(
                    "ornek-app-01/prod/deploy-ssh-key",
                    "ornek-lb/prod/panel-admin",
                    "ornek-proje/prod/spring.datasource.password");
    private static final String FINGERPRINT =
            """
            MATCH (n) OPTIONAL MATCH (n)-[r]->()
            WITH count(DISTINCT n) AS nodes, count(r) AS relations
            CALL db.labels() YIELD label
            WITH nodes, relations, collect(label) AS labels
            RETURN nodes, relations, labels,
                   COLLECT { MATCH (e:Entity) RETURN e.id + '=' + e.status } AS statuses
            """;
    private static final String INDEXES = "SHOW INDEXES YIELD name RETURN collect(name) AS names";

    @Autowired private KnowledgeQueries queries;
    @Autowired private EmbeddedNeo4jStore store;

    @ParameterizedTest(name = "a missed write fails: {0}")
    @ValueSource(
            strings = {
                "CREATE (n:Entity {id: 'guard-miss'}) RETURN n.id",
                "MERGE (n:Entity {id: 'guard-miss'}) RETURN n.id",
                "MATCH (e:Entity) SET e.status = 'guard-miss' RETURN count(e)",
                "MATCH (e:Entity) REMOVE e.status RETURN count(e)",
                "MATCH (f:Fact) DETACH DELETE f RETURN count(*)",
                "CALL db.createLabel('GuardMiss')",
                "CREATE INDEX guard_miss FOR (n:Entity) ON (n.summary)"
            })
    void a_write_that_passes_the_guard_fails_and_leaves_the_graph_unchanged(String query) {
        Map<String, Object> before = fingerprint();

        Throwable failure = catchThrowable(() -> queries.cypher(query));

        assertThat(failure).as("the write ran: %s", query).isNotNull();
        assertThat(rootMessage(failure))
                .as("refused by the read-only transaction, not by another check")
                .containsIgnoringCase("not allowed");
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    void rows_are_the_columns_of_the_query_in_order() {
        List<Map<String, Object>> rows =
                queries.cypher(
                        "MATCH (e:Entity {id: 'ornek-db'}) RETURN e.id AS id, e.status AS status");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().keySet()).containsExactly("id", "status");
        assertThat(rows.getFirst())
                .containsEntry("id", "ornek-db")
                .containsEntry("status", "observed");
    }

    /**
     * Whole nodes, maps, paths, lists, the property itself and dynamic lookups that build its name:
     * none of the rows may hold the value. The secret id must still come through, or the test
     * passes on a query that matched nothing.
     */
    @ParameterizedTest(name = "no secret value in: {0}")
    @ValueSource(
            strings = {
                "MATCH (s:Secret) RETURN s",
                "MATCH (s:Secret) RETURN s.id AS id, properties(s) AS props",
                "MATCH (s:Secret) RETURN s.id AS id, s {.*} AS map",
                "MATCH (s:Secret) RETURN s.id AS id, s.secret_value",
                "MATCH (s:Secret) RETURN s.id AS id, s.secret_value AS x",
                "MATCH (s:Secret) RETURN s.id AS id, 'v=' + s.secret_value AS x",
                "MATCH (s:Secret) RETURN s.id AS id, {wrapped: s.secret_value} AS x",
                "MATCH (s:Secret) RETURN s.id AS id, s['secret' + '_value'] AS x",
                "MATCH (s:Secret) RETURN s.id AS id, s['\\u0073ecret_value'] AS x",
                "MATCH (s:Secret) UNWIND keys(s) AS k RETURN s.id AS id, k, s[k] AS x",
                "MATCH (e:Entity)-[:HAS_SECRET]->(s:Secret) RETURN e.id, collect(s) AS secrets",
                "MATCH p = (:Entity)-[:HAS_SECRET]->(s:Secret) RETURN p",
                "MATCH (s:Secret) RETURN [s, [s]] AS nested"
            })
    void rows_never_hold_a_secret_value(String query) {
        writeFakeSecretValues();

        String rows = String.valueOf(queries.cypher(query));

        assertThat(rows).as("the query reached the secrets").contains("ornek-lb/prod/panel-admin");
        assertThat(rows).doesNotContain(MARKER);
    }

    private void writeFakeSecretValues() {
        Number written =
                (Number)
                        store.database()
                                .executeTransactionally(
                                        "MATCH (s:Secret) WHERE s.id IN $ids"
                                                + " SET s.secret_value = $value RETURN count(s) AS n",
                                        Map.of("ids", SEED_SECRET_IDS, "value", FAKE_VALUE),
                                        result -> result.next().get("n"));
        assertThat(written.intValue())
                .as("fixture precondition: every seed secret holds the value")
                .isEqualTo(SEED_SECRET_IDS.size());
    }

    private Map<String, Object> fingerprint() {
        Map<String, Object> state = new HashMap<>(store.read(tx -> tx.execute(FINGERPRINT).next()));
        // store.read refuses SHOW commands (kb_cypher must), so the indexes come from a plain
        // transaction.
        state.put(
                "indexes",
                store.database()
                        .executeTransactionally(
                                INDEXES, Map.of(), result -> result.next().get("names")));
        return state;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}
