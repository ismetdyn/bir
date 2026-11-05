package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.ExampleQueries;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant 7 and SPEC §13.5: kb_cypher runs only read queries; writes, LOAD CSV, admin procedures
 * and any access to {@code secret_value} are refused, and relation types come only from
 * schema/relations.yaml. {@code check} answers the reason a query is refused, empty if it may run.
 *
 * <p>Measured on Neo4j 2026.10.1 in the store's read-only transaction (G6 probe): LOAD CSV reads
 * local files, {@code CALL dbms.components()} and {@code SHOW SETTINGS} run, and {@code s['secret'
 * + '_value']} or the name written with a Unicode escape in a string literal return the value. The
 * read-only transaction stops none of these, so the guard has to.
 */
class CypherGuardTest extends RunningServerTestBase {

    private static final char DOTLESS_I = 0x0131;
    private static final char COMBINING_ACUTE = 0x0301;
    private static final char UNDERTIE = 0x203F;
    private static final char NO_BREAK_SPACE = 0x00A0;
    // What Neo4j 2026.10.1 reads as whitespace between LOAD and CSV, besides the ASCII ones.
    private static final int[] UNICODE_SPACES = {
        0x1C, 0x1F, 0xA0, 0x1680, 0x2000, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000
    };

    @Autowired private CypherGuard guard;

    /** Negatives first: every row of refusedChanges, refusedReads and refusedTricks is refused. */
    static Stream<Arguments> refusedChanges() {
        return Stream.of(
                        rows(
                                "write",
                                "CREATE (n:Entity {id: 'guard-miss'}) RETURN n.id",
                                "create (n:Entity {id: 'guard-miss'})",
                                "CrEaTe (n:Entity {id: 'guard-miss'})",
                                "MERGE (n:Entity {id: 'guard-miss'})",
                                "MATCH (e:Entity {id: 'ornek-db'}) MERGE (e)-[:RELATED]->(:Entity {id: 'x'})",
                                "MATCH (e:Entity) SET e.status = 'archived'",
                                "MATCH (e:Entity) SET e += {status: 'archived'}",
                                "MATCH (e:Entity) SET e:Archived",
                                "MATCH (f:Fact) DELETE f",
                                "MATCH (e:Entity) DETACH DELETE e",
                                "MATCH (e:Entity) REMOVE e.status",
                                "MATCH (e:Entity) REMOVE e:Entity",
                                "FOREACH (i IN [1] | CREATE (:Entity {id: 'guard-miss'}))",
                                "MATCH (e:Entity) CALL (e) { SET e.status = 'x' } RETURN e.id",
                                "CREATE INDEX sneaked FOR (n:Entity) ON (n.summary)",
                                "DROP INDEX kb_fulltext"),
                        rows(
                                "batch or file",
                                // Read-only by EXPLAIN, still a batch the guard refuses (task G6).
                                "UNWIND range(1, 3) AS i CALL (i) { RETURN i AS j } IN TRANSACTIONS RETURN j",
                                "MATCH (e:Entity) CALL (e) { SET e.status = 'x' } IN TRANSACTIONS",
                                "USING PERIODIC COMMIT 10 LOAD CSV FROM 'file:///etc/hostname' AS row RETURN row",
                                "LOAD CSV FROM 'file:///etc/hostname' AS row RETURN row",
                                "load csv with headers from 'https://example.com/x.csv' as row return row",
                                "LOAD\tCSV FROM 'file:///etc/hostname' AS row RETURN row",
                                "MATCH (e:Entity) WITH e LIMIT 1 LOAD CSV FROM 'file:///etc/hostname' AS r RETURN r"),
                        rows(
                                "procedure",
                                "CALL db.createLabel('GuardMiss')",
                                "CALL db.createProperty('guardMiss')",
                                "CALL db.clearQueryCaches()",
                                "CALL dbms.components() YIELD name RETURN name",
                                "call dbms.components() yield name return name",
                                "CALL dbms.info()",
                                // Read-only by EXPLAIN, but an admin command that shows paths and
                                // config.
                                "SHOW SETTINGS YIELD name, value RETURN name, value"))
                .flatMap(rows -> rows);
    }

    static Stream<Arguments> refusedReads() {
        return Stream.of(
                        rows(
                                // Any form of the name, and dynamic lookups that build it, also as
                                // a WHERE oracle that answers with ids only.
                                "secret_value",
                                "MATCH (s:Secret) RETURN s.secret_value",
                                "MATCH (s:Secret) RETURN s.secret_value AS x",
                                "MATCH (s:Secret) RETURN s.SECRET_VALUE",
                                "MATCH (s:Secret) RETURN s.`secret_value`",
                                "MATCH (s:Secret) RETURN s['secret_value']",
                                "MATCH (s:Secret) RETURN s[\"secret_value\"]",
                                "MATCH (s:Secret) RETURN s {.secret_value}",
                                "MATCH (s:Secret) RETURN properties(s).secret_value",
                                "MATCH (s:Secret) WHERE s.secret_value STARTS WITH 'k' RETURN s.id",
                                "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN count(s)",
                                "MATCH (s:Secret) RETURN s.id /* secret_value */",
                                "MATCH (s:Secret) WHERE s['secret' + '_value'] STARTS WITH 'k' RETURN s.id",
                                "MATCH (s:Secret) WHERE s['\\u0073ecret_value'] STARTS WITH 'k' RETURN s.id",
                                "MATCH (s:Secret) WHERE properties(s)['secret' + '_value'] STARTS WITH 'k'"
                                        + " RETURN s.id",
                                "MATCH (s:Secret) WHERE any(k IN keys(s) WHERE s[k] STARTS WITH 'k')"
                                        + " RETURN s.id",
                                "MATCH (s:Secret) UNWIND keys(s) AS k WITH s, k WHERE s[k] STARTS WITH 'k'"
                                        + " RETURN s.id",
                                "MATCH (s:Secret) WITH s {.*} AS m UNWIND keys(m) AS k WITH m, k"
                                        + " WHERE m[k] STARTS WITH 'k' RETURN m.id"),
                        rows(
                                "relation type",
                                "MATCH (a:Entity)-[:KNOWS]->(b:Entity) RETURN a.id, b.id",
                                "MATCH (a:Entity)-[:DEPENDS_ON|KNOWS]->(b) RETURN b.id",
                                "MATCH (a:Entity)-[:`SECRET_LINK`]->(b) RETURN b.id",
                                "MATCH (a:Entity)-[:DEPENDS_ON*1..3]->()-[:KNOWS]-(b) RETURN b.id",
                                "MATCH p = (a:Entity)-[:RUNS]->()<-[r:OWNS]-(b) RETURN b.id"),
                        rows(
                                // A keyword after a comment, or a second statement, is still there.
                                "trick",
                                "MATCH (n:Entity) RETURN n.id // read only\nCREATE (:Entity {id: 'guard-miss'})",
                                "/* read only */ CREATE (:Entity {id: 'guard-miss'})",
                                "MATCH (n:Entity) /* RETURN n.id */ DETACH DELETE n",
                                "MATCH (n:Entity) RETURN n.id; CREATE (:Entity {id: 'guard-miss'})",
                                "MATCH (n:Entity) RETURN n.id; MATCH (m:Entity) RETURN m.id",
                                "MATCH (n RETURN n",
                                ""))
                .flatMap(rows -> rows);
    }

    /**
     * Text the guard read otherwise than Neo4j does. Each one but {@code CALL dbms.components()}
     * ran on Neo4j 2026.10.1 past the guard (G6 review probe); the secret reads returned the value
     * reversed, which the row filter does not see.
     */
    static Stream<Arguments> refusedTricks() {
        return Stream.of(
                        rows(
                                // Neo4j ends a line comment at CR as well as LF.
                                "comment ended by CR",
                                "// x\rLOAD CSV FROM 'file:///etc/hostname' AS r RETURN r",
                                "// x\rCALL dbms.components()",
                                "// x\rSHOW SETTINGS YIELD name RETURN name",
                                "// x\r" + readByName("m")),
                        rows(
                                // Neo4j reads these as whitespace, the LOAD\s+CSV rule did not.
                                "unicode whitespace",
                                IntStream.of(UNICODE_SPACES)
                                        .mapToObj(
                                                space ->
                                                        "LOAD"
                                                                + (char) space
                                                                + "CSV FROM 'file:///etc/hostname'"
                                                                + " AS r RETURN r")
                                        .toArray(String[]::new)),
                        rows(
                                // A keyword is a valid variable or map key in Neo4j, so a bracket
                                // after it can be a subscript.
                                "keyword as a name",
                                "MATCH (s:Secret) WITH s, properties(s) AS by UNWIND keys(by) AS k"
                                        + " RETURN s.id, k, reverse(by[k]) AS v",
                                readByName("contains"),
                                readByName("yield"),
                                readByName("when"),
                                "MATCH (when:Secret) UNWIND keys(when) AS k RETURN reverse(when[k]) AS v",
                                "MATCH (s:Secret) RETURN [when IN [properties(s)] | reverse(when['id'])]",
                                "MATCH (s:Secret) WITH s, {yield: properties(s)} AS m UNWIND keys(s) AS k"
                                        + " RETURN reverse(m.yield[k]) AS v",
                                // Upper-cased, a dotless i makes WITH; Neo4j sees a name.
                                readByName("w" + DOTLESS_I + "th")),
                        rows(
                                // A name that ends in a combining mark or a connector, or ends in
                                // a keyword after one.
                                "identifier end",
                                readByName("m" + COMBINING_ACUTE),
                                readByName("m" + UNDERTIE),
                                readByName("x" + COMBINING_ACUTE + "when")),
                        rows(
                                // The parser overflowed the stack, an Error no caller catches.
                                "nesting",
                                "RETURN " + "(".repeat(3000) + "1" + ")".repeat(3000) + " AS x"))
                .flatMap(rows -> rows);
    }

    /**
     * Every example query of the docs; eval q14; fixed edges and secret metadata; words that only
     * look like keywords: inside a string, an identifier or an alias they are data, and a text
     * match on CREATE or DELETE would refuse them.
     */
    static Stream<Arguments> allowed() {
        return Stream.concat(
                rows("example", ExampleQueries.fromDocs().toArray(String[]::new)),
                rows(
                        "read",
                        "MATCH (sf:SourceFile {path:'src/main/resources/application-prod.yml'})"
                                + "<-[:FROM]-(f:Fact)<-[:HAS_FACT]-(e:Entity)"
                                + " WHERE f.valid_to IS NULL RETURN DISTINCT e.id",
                        "match (e:Entity) return e.id limit 5",
                        "MATCH (e:Entity)-[:HAS_SECRET]->(s:Secret) RETURN e.id, s.id, s.name, s.env",
                        "MATCH (f:Fact)-[:SUPERSEDES]->(old:Fact) RETURN f.value, old.value",
                        "MATCH (r:Repo)<-[:HAS_REPO]-(p:Project) RETURN p.id, r.id",
                        "MATCH (d:Decision) WHERE d.rule CONTAINS 'delete' OR d.rule CONTAINS 'create'"
                                + " RETURN d.id",
                        "MATCH (e:Entity) WHERE e.id <> 'deleted' RETURN e.id AS created,"
                                + " e.status AS settled",
                        "MATCH (f:Fact) RETURN f.updated_at ORDER BY f.updated_at DESC LIMIT 3",
                        "MATCH (e:Entity) WHERE e.summary CONTAINS 'secret' RETURN e.id",
                        "MATCH (a:Entity)-[r:OWNED_BY|AUTHENTICATES_WITH|ROUTES_TO|RELATED]-(b)"
                                + " RETURN b.id",
                        // CRLF line ends, a comment before one, list literals after keywords and
                        // a Unicode space inside a string.
                        "MATCH (e:Entity) // entities\r\nWHERE e.id IN ['ornek-db'] RETURN e.id\r\n",
                        "MATCH (e:Entity) RETURN CASE WHEN e.status = 'archived' THEN ['a']"
                                + " ELSE [] END AS x",
                        "MATCH (e:Entity) RETURN collect(DISTINCT [e.id, e.status]) AS pairs",
                        "MATCH (e:Entity) WITH e {.id, by: 1} AS m RETURN [m.id] AS ids ORDER BY ids",
                        "MATCH (e:Entity) WHERE e.summary CONTAINS 'a"
                                + NO_BREAK_SPACE
                                + "b' RETURN e.id"));
    }

    @ParameterizedTest(name = "refuses {0}: {1}")
    @MethodSource({"refusedChanges", "refusedReads", "refusedTricks"})
    void refuses_the_query(String kind, String query) {
        Optional<String> reason = guard.check(query);

        assertThat(reason).as("let through: %s", query).isPresent();
        assertThat(reason.get()).as("reason").isNotBlank();
        assertThat(reason.get())
                .as("the reason is for the agent, not a stack trace")
                .doesNotContain(List.of("org.neo4j", "Exception", "\tat "));
    }

    @ParameterizedTest(name = "allows {0}: {1}")
    @MethodSource("allowed")
    void allows_the_query(String kind, String query) {
        assertThat(guard.check(query)).as("refused: %s", query).isEmpty();
    }

    // Every property of each secret, read by a computed key under the variable {@code name}.
    private static String readByName(String name) {
        return ("MATCH (s:Secret) WITH properties(s) AS %1$s UNWIND keys(%1$s) AS k"
                        + " RETURN reverse(%1$s[k]) AS v")
                .formatted(name);
    }

    private static Stream<Arguments> rows(String kind, String... queries) {
        return Stream.of(queries).map(query -> arguments(kind, query));
    }
}
