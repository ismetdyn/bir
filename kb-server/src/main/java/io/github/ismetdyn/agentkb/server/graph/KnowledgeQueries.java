package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.render.EntityView;
import io.github.ismetdyn.agentkb.core.render.ResultList;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.core.render.SecretView;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Transaction;
import org.springframework.stereotype.Component;

/**
 * The read side of the graph for the read tools, always through {@link EmbeddedNeo4jStore#read}.
 * Queries project the properties they need; none returns a whole node, so {@code secret_value}
 * cannot ride along (invariant 2). The agent's own query ({@link #cypher}) can, so its rows are
 * filtered. Every answer row comes through ReadRows, which masks a stored value in it.
 */
@Component
public class KnowledgeQueries {

    /** Which way kb_related follows relations from the start record. */
    public enum Direction {
        OUT("-[*1..%d]->"),
        IN("<-[*1..%d]-"),
        BOTH("-[*1..%d]-");

        private final String pattern;

        Direction(String pattern) {
            this.pattern = pattern;
        }
    }

    public static final int MAX_DEPTH = 3;

    // Same name as in write/GraphSchema; graph cannot import write (write depends on graph).
    private static final String FULLTEXT_INDEX = "kb_fulltext";
    // Lucene query syntax; the agent's words are text, never operators (ReadToolContractTest).
    private static final Pattern LUCENE_SPECIAL =
            Pattern.compile("[\\\\+\\-!():^\\[\\]\"{}~*?|&/]");
    // ListQueries names an entity's type the same way.
    static final String TYPE_OF = "coalesce([l IN labels(%s) WHERE l <> 'Entity'][0], 'Entity')";

    // RESOLVE's id half alone: an index seek, where RESOLVE's alias match scans every entity.
    private static final String RESOLVE_ID =
            "MATCH (e:Entity) WHERE e.id IN $ids RETURN e.id AS id ORDER BY id LIMIT 1";
    private static final String RESOLVE =
            """
            MATCH (e:Entity) WHERE e.id IN $ids OR any(a IN coalesce(e.aliases, []) WHERE a IN $ids)
            RETURN e.id AS id ORDER BY CASE WHEN e.id IN $ids THEN 0 ELSE 1 END, e.id LIMIT 1
            """;
    private static final String ENTITY =
            """
            MATCH (e:Entity {id: $id})
            RETURN e.id AS entity, %s AS type, e.status AS status, e.summary AS summary,
                   [a IN coalesce(e.aliases, []) | a] AS aliases
            """
                    .formatted(TYPE_OF.formatted("e"));
    private static final String FACTS =
            """
            MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact)
            OPTIONAL MATCH (f)-[from:FROM]->(src)
            WITH f, head(collect({id: src.id, path: src.path, repo: src.repo, line: from.line})) AS src
            OPTIONAL MATCH (ep:Episode {id: f.episode})
            RETURN f {.id, .key, .value, .env, .source, .valid_from, .observed_at, .valid_to, .status}
                     AS fact,
                   [(f)-[:TARGET]->(t) | t.id][0] AS target,
                   src.id AS originId, src.path AS originPath, src.repo AS originRepo,
                   src.line AS originLine,
                   ep.client AS episodeClient, ep.started_at AS episodeAt,
                   ep.transcript_path AS episodeTranscript
            ORDER BY f.key, f.env,
                     CASE WHEN f.valid_to IS NOT NULL OR f.status = 'ok' THEN 0
                          WHEN f.status = 'unverified' THEN 1 ELSE 2 END,
                     f.valid_from, f.id
            """;
    private static final String EPISODE_OF =
            """
            MATCH (e:Episode {client: $client, session_id: $session})
            RETURN e.id AS id ORDER BY e.id LIMIT 1
            """;
    private static final String RELATIONS =
            """
            MATCH (e:Entity {id: $id})-[r]-(o:Entity)
            RETURN r.id AS id, type(r) AS type, startNode(r) = e AS outgoing, o.id AS other,
                   r.env AS env,
                   r.source AS source, r.valid_from AS validFrom,
                   coalesce(r.observed_at, r.valid_from) AS observedAt, r.valid_to AS validTo
            ORDER BY r.valid_from, type(r), o.id
            """;
    private static final String SECRETS =
            """
            MATCH (:Entity {id: $id})-[:HAS_SECRET]->(s:Secret)
            RETURN s.id AS secret, s.name AS name, s.env AS env ORDER BY s.id
            """;
    private static final String SEARCH =
            """
            CALL db.index.fulltext.queryNodes($index, $query) YIELD node, score
            WHERE $env IS NULL OR NOT node:Fact OR node.env = $env
            OPTIONAL MATCH (owner:Entity)-[:HAS_FACT]->(node)
            WITH node, score, coalesce(owner, node) AS record
            RETURN score, record.id AS id, record.id AS record, %s AS type, record:Entity AS isEntity,
                   record.status AS status, node:Signal AS isSignal,
                   coalesce(record.summary, record.rule, record.title, record.name, record.payload)
                     AS text,
                   CASE WHEN node:Fact THEN node {.id, .key, .value, .env, .source, .valid_from,
                     .observed_at, .valid_to, .status} END AS fact,
                   CASE WHEN node:Fact THEN [(node)-[:TARGET]->(t) | t.id][0] END AS target,
                   [(record)-[:HAS_FACT]->(c:Fact) WHERE node:Fact AND c <> node
                     AND c.valid_to IS NULL AND c.status <> 'conflict' AND c.key = node.key
                     AND c.env = node.env | c.value] AS kept
            """
                    .formatted(TYPE_OF.formatted("record"));
    // Every record on a path is an entity and appears once; the relations between entities are
    // the vocabulary ones (SPEC §6.2), which always carry source and valid_from.
    private static final String RELATED =
            """
            MATCH p = (s:Entity {id: $id})%s(x:Entity)
            WHERE all(n IN nodes(p) WHERE n:Entity AND single(m IN nodes(p) WHERE m = n))
              AND ($rel IS NULL OR all(r IN relationships(p) WHERE type(r) = $rel))
            WITH nodes(p) AS ns, relationships(p) AS rs
            RETURN [i IN range(0, size(rs) - 1) | {
                     type: type(rs[i]), outgoing: startNode(rs[i]) = ns[i], other: ns[i + 1].id,
                     otherType: %s, status: ns[i + 1].status,
                     env: rs[i].env, source: rs[i].source, validFrom: rs[i].valid_from,
                     observedAt: coalesce(rs[i].observed_at, rs[i].valid_from),
                     validTo: rs[i].valid_to}] AS steps
            """;

    // Current facts last seen before $before, or not ok (conflict, unverified) whatever their age.
    // Rows have the shape of SEARCH, so they read as search hits; a fact that is not ok ranks
    // first. STALE takes the owners by label (kb_stale), STALE_NEAR an entity and its neighbours
    // by raw id (the brief).
    private static final String STALE_ROWS =
            """
            MATCH (e)-[:HAS_FACT]->(f:Fact)
            WHERE f.valid_to IS NULL AND (f.observed_at < $before OR f.status <> 'ok')
            RETURN CASE WHEN f.status <> 'ok' THEN 1.0 ELSE 0.0 END AS score, e.id AS id,
                   e.id AS record, %s AS type, true AS isEntity, e.status AS status,
                   false AS isSignal, e.summary AS text,
                   f {.id, .key, .value, .env, .source, .valid_from, .observed_at, .valid_to,
                     .status} AS fact,
                   [(f)-[:TARGET]->(t) | t.id][0] AS target,
                   [(e)-[:HAS_FACT]->(c:Fact) WHERE c <> f AND c.valid_to IS NULL
                     AND c.status <> 'conflict' AND c.key = f.key AND c.env = f.env | c.value]
                     AS kept
            ORDER BY e.id, f.key, f.env, f.valid_from, f.id
            """
                    .formatted(TYPE_OF.formatted("e"));
    private static final String STALE =
            "MATCH (e:Entity) WHERE any(l IN labels(e) WHERE l IN $labels) WITH e " + STALE_ROWS;
    private static final String STALE_NEAR =
            """
            MATCH (p:Entity {id: $id})
            MATCH (e:Entity) WHERE e = p OR EXISTS { (e)--(p) }
            WITH e
            """
                    + STALE_ROWS;

    /** SPEC §13.5: kb_cypher rows; {@link #cypher} returns one more to show the cut. */
    public static final int CYPHER_ROWS = 200;

    private static final Duration CYPHER_TIMEOUT = Duration.ofSeconds(5);

    private final EmbeddedNeo4jStore store;

    public KnowledgeQueries(EmbeddedNeo4jStore store) {
        this.store = store;
    }

    /** The id of the Episode SessionStart opened for {@code (client, sessionId)} (ADR-0023). */
    public Optional<String> episode(String client, String sessionId) {
        Map<String, Object> params = Map.of("client", client, "session", sessionId);
        return store.read(tx -> ReadRows.ids(tx, EPISODE_OF, params).stream().findFirst());
    }

    /** The entity with this id or alias, in any case (Ids.normalize). */
    public Optional<EntityView> entity(String idOrAlias) {
        return store.read(tx -> resolve(tx, idOrAlias).map(id -> view(tx, id)));
    }

    /**
     * Fulltext hits, one per record: the best of its matches, current before history. {@code text}
     * is plain words; Lucene operators in it are escaped.
     */
    public List<Hit> search(String text, @Nullable String type, @Nullable String env) {
        Map<String, Object> params = new HashMap<>();
        params.put("index", FULLTEXT_INDEX);
        params.put("query", LUCENE_SPECIAL.matcher(Ids.normalize(text)).replaceAll("\\\\$0"));
        params.put("env", env == null ? null : Ids.normalize(env));
        // Keyed by the raw id: two records whose ids differ only inside a masked value stay two.
        List<Map.Entry<String, Hit>> hits =
                store.read(
                        tx ->
                                ReadRows.in(tx)
                                        .rows(
                                                SEARCH,
                                                params,
                                                row ->
                                                        Map.entry(
                                                                (String) row.get("id"),
                                                                KnowledgeQueriesRows.searchHit(
                                                                        row))));
        Map<String, Hit> best = new LinkedHashMap<>();
        for (Map.Entry<String, Hit> hit : hits) {
            if (type == null || Ids.normalize(type).equals(Ids.normalize(hit.getValue().type()))) {
                best.merge(
                        hit.getKey(),
                        hit.getValue(),
                        (a, b) -> ResultList.ORDER.compare(a, b) <= 0 ? a : b);
            }
        }
        return List.copyOf(best.values());
    }

    /**
     * Every simple path of up to {@code depth} relations from the entity, as a hit about the record
     * it reaches; empty if there is no such entity.
     */
    public Optional<List<Hit>> related(
            String idOrAlias, @Nullable String rel, Direction direction, int depth) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth out of range: " + depth);
        }
        String query =
                RELATED.formatted(
                        direction.pattern.formatted(depth), TYPE_OF.formatted("ns[i + 1]"));
        return store.read(
                tx ->
                        resolve(tx, idOrAlias)
                                .map(
                                        id -> {
                                            Map<String, Object> params = new HashMap<>();
                                            params.put("id", id);
                                            params.put("rel", rel);
                                            ReadRows read = ReadRows.in(tx);
                                            return read.rows(
                                                    query,
                                                    params,
                                                    row -> path(read.shown(id), row));
                                        }));
    }

    /**
     * Facts to verify again (docs/MCP-TOOLS.md kb_stale): current facts of entities with one of
     * {@code labels} last seen before {@code observedBefore}, and every conflict or unverified one.
     * Archived entities are not left out (invariant 12); their hits are marked.
     */
    public List<Hit> stale(List<String> labels, Instant observedBefore) {
        Map<String, Object> params =
                Map.of("labels", List.copyOf(labels), "before", Times.format(observedBefore));
        return store.read(
                tx -> ReadRows.in(tx).rows(STALE, params, KnowledgeQueriesRows::searchHit));
    }

    /**
     * {@link #stale} of the entity {@code id} and its direct neighbours, found by the raw id (the
     * brief, SPEC §6.4).
     */
    public List<Hit> staleNear(String id, Instant observedBefore) {
        Map<String, Object> params = Map.of("id", id, "before", Times.format(observedBefore));
        return store.read(
                tx -> ReadRows.in(tx).rows(STALE_NEAR, params, KnowledgeQueriesRows::searchHit));
    }

    /**
     * Runs a query kb_cypher let through, unchecked: CypherGuard is the caller's job. It runs in a
     * read-only transaction that ends after 5 s (SPEC §13.5), and no row holds a secret value. At
     * most {@link #CYPHER_ROWS} + 1 rows, in the column order of the query; the extra one means
     * there were more.
     */
    public List<Map<String, Object>> cypher(String query) {
        return store.read(CYPHER_TIMEOUT, tx -> ReadRows.in(tx).cypher(query, CYPHER_ROWS + 1));
    }

    private static Hit path(String startId, Map<String, Object> row) {
        return ResultList.path(
                startId,
                ((List<?>) row.get("steps"))
                        .stream()
                                .map(step -> KnowledgeQueriesRows.step((Map<?, ?>) step))
                                .toList());
    }

    /**
     * The id of the entity with this id or alias, in any case; an id wins over an alias. The write
     * path resolves inside its own transaction with it (ARCHITECTURE §5 step 2).
     */
    public static Optional<String> resolve(Transaction tx, String idOrAlias) {
        String raw = idOrAlias.strip();
        List<String> ids = List.of(raw, Ids.normalize(raw)).stream().distinct().toList();
        Map<String, Object> params = Map.of("ids", ids);
        // An id wins over an alias (RESOLVE's order), so RESOLVE runs only when no id matches.
        return ReadRows.ids(tx, RESOLVE_ID, params).stream()
                .findFirst()
                .or(() -> ReadRows.ids(tx, RESOLVE, params).stream().findFirst());
    }

    private static EntityView view(Transaction tx, String id) {
        Map<String, Object> params = Map.of("id", id);
        ReadRows read = ReadRows.in(tx);
        Map<String, Object> entity = read.rows(ENTITY, params, row -> row).getFirst();
        // The id from the row, masked like every text of it; the parameter only finds records.
        return new EntityView(
                (String) entity.get("entity"),
                (String) entity.get("type"),
                String.valueOf(entity.get("status")),
                (String) entity.get("summary"),
                KnowledgeQueriesRows.strings(entity.get("aliases")),
                read.rows(FACTS, params, KnowledgeQueriesRows::factView),
                read.rows(RELATIONS, params, KnowledgeQueriesRows::relation),
                read.rows(
                        SECRETS,
                        params,
                        row ->
                                new SecretView(
                                        (String) row.get("secret"),
                                        (String) row.get("name"),
                                        String.valueOf(row.get("env")))));
    }
}
