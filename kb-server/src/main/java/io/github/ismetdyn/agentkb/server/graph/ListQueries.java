package io.github.ismetdyn.agentkb.server.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The lists the clients review (docs/CLIENTS.md §1): waiting conflicts, proposals, the latest
 * batches and the secrets. Queries project the properties they need; the secret list asks only
 * whether {@code secret_value} is there, never for it (invariant 2). Each list is whole and in its
 * order; the caller applies the limit.
 */
@Component
public class ListQueries {

    /** A value waiting as a conflict next to the {@code current} values of its key and env. */
    public record Conflict(
            String id,
            String entity,
            String key,
            String env,
            String value,
            String source,
            String validFrom,
            List<String> current) {}

    /** {@code by} is the source that proposed it; {@code target} the id of its ABOUT record. */
    public record Proposal(
            String id,
            String kind,
            String status,
            String by,
            @Nullable String target,
            String content,
            @Nullable String evidence,
            @Nullable String reviewNote) {}

    /**
     * One batch: the last time it wrote, the sources of what it opened (user for a user's close or
     * revision), its counts and the entities it touched.
     */
    public record Batch(
            String batch,
            String at,
            List<String> sources,
            int opened,
            int closed,
            int revised,
            List<String> entities) {}

    /** {@code since} is an ISO-8601 time as Times.format writes it. */
    public record ActivityFilter(
            @Nullable String source, @Nullable String batch, @Nullable String since) {}

    public record SecretEntry(String id, String name, String env, boolean hasValue) {}

    private static final String CONFLICTS =
            """
            MATCH (e:Entity)-[:HAS_FACT]->(f:Fact)
            WHERE f.valid_to IS NULL AND f.status = 'conflict'
            RETURN f.id AS id, e.id AS entity, f.key AS key, coalesce(f.env, 'default') AS env,
                   f.value AS value, f.source AS source, f.valid_from AS validFrom,
                   [(e)-[:HAS_FACT]->(c:Fact) WHERE c.valid_to IS NULL AND c.status <> 'conflict'
                     AND c.key = f.key AND coalesce(c.env, 'default') = coalesce(f.env, 'default')
                     | c.value] AS current
            ORDER BY f.valid_from, f.id
            """;
    private static final String PROPOSALS =
            """
            MATCH (p:Proposal)
            RETURN p.id AS id, coalesce(p.kind, '') AS kind, coalesce(p.status, '') AS status,
                   coalesce(p.by, '') AS by, [(p)-[:ABOUT]->(t) | t.id][0] AS target,
                   coalesce(p.content, '') AS content, p.evidence AS evidence,
                   p.review_note AS reviewNote
            ORDER BY CASE WHEN p.status = 'open' THEN 0 ELSE 1 END, p.id DESC
            """;
    // Every record of a batch, each counted once: what it opened (batch), what it closed, and what
    // it revised (Revision, ADR-0025 decision 15). A close is a user edit's closed_batch (decision
    // 10) or a fact the batch superseded (SUPERSEDES from one of its records), as WriteResult and
    // ScanResult count them; accept-new does both for one fact. Relations count only between
    // entities, the vocabulary ones; HAS_FACT and the like belong to their node.
    private static final String ACTIVITY =
            """
            CALL () {
              MATCH (n) WHERE n.batch IS NOT NULL AND NOT n:Revision
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT|HAS_SECRET]->(n)
              RETURN n.batch AS batch, coalesce(n.source, n.by) AS source, n.updated_at AS at,
                     'opened' AS change, elementId(n) AS rec,
                     coalesce(owner.id, CASE WHEN n:Entity THEN n.id END) AS entity
              UNION ALL
              MATCH (a:Entity)-[r]->(:Entity) WHERE r.batch IS NOT NULL
              RETURN r.batch AS batch, r.source AS source, r.updated_at AS at,
                     'opened' AS change, elementId(r) AS rec, a.id AS entity
              UNION ALL
              MATCH (n) WHERE n.closed_batch IS NOT NULL
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT]->(n)
              RETURN n.closed_batch AS batch, 'user' AS source, n.valid_to AS at,
                     'closed' AS change, elementId(n) AS rec, owner.id AS entity
              UNION ALL
              MATCH (a:Entity)-[r]->(:Entity) WHERE r.closed_batch IS NOT NULL
              RETURN r.closed_batch AS batch, 'user' AS source, r.valid_to AS at,
                     'closed' AS change, elementId(r) AS rec, a.id AS entity
              UNION ALL
              MATCH (n)-[:SUPERSEDES]->(old)
              WHERE n.batch IS NOT NULL AND old.valid_to IS NOT NULL
                AND (old.batch IS NULL OR old.batch <> n.batch)
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT]->(old)
              RETURN n.batch AS batch, n.source AS source, old.valid_to AS at,
                     'closed' AS change, elementId(old) AS rec, owner.id AS entity
              UNION ALL
              MATCH (rv:Revision)-[:REVISES]->(x)
              RETURN rv.batch AS batch, 'user' AS source, rv.updated_at AS at,
                     'revised' AS change, elementId(rv) AS rec,
                     CASE WHEN x:Entity THEN x.id END AS entity
            }
            WITH batch, coalesce(max(at), '') AS at, collect(DISTINCT source) AS sources,
                 count(DISTINCT CASE WHEN change = 'opened' THEN rec END) AS opened,
                 count(DISTINCT CASE WHEN change = 'closed' THEN rec END) AS closed,
                 count(DISTINCT CASE WHEN change = 'revised' THEN rec END) AS revised,
                 collect(DISTINCT entity) AS entities
            WHERE ($batch IS NULL OR batch = $batch) AND ($since IS NULL OR at >= $since)
              AND ($source IS NULL OR $source IN sources)
            RETURN batch, at, sources, opened, closed, revised, entities
            ORDER BY at DESC, batch DESC
            """;
    private static final String SECRETS =
            """
            MATCH (s:Secret) WHERE s.id STARTS WITH $prefix
            RETURN s.id AS id, coalesce(s.name, '') AS name, coalesce(s.env, 'default') AS env,
                   s.secret_value IS NOT NULL AS hasValue
            ORDER BY s.id
            """;
    // An activity row names at most this many entities; the batch itself has them all.
    private static final int ENTITIES_SHOWN = 10;

    private final EmbeddedNeo4jStore store;

    public ListQueries(EmbeddedNeo4jStore store) {
        this.store = store;
    }

    /** Every value waiting as a conflict, oldest first. */
    public List<Conflict> conflicts() {
        return read(
                CONFLICTS,
                Map.of(),
                row ->
                        new Conflict(
                                text(row, "id"),
                                text(row, "entity"),
                                text(row, "key"),
                                text(row, "env"),
                                text(row, "value"),
                                text(row, "source"),
                                text(row, "validFrom"),
                                KnowledgeQueriesRows.strings(row.get("current"))));
    }

    /** Every proposal: open ones first, then newest first. */
    public List<Proposal> proposals() {
        return read(
                PROPOSALS,
                Map.of(),
                row ->
                        new Proposal(
                                text(row, "id"),
                                text(row, "kind"),
                                text(row, "status"),
                                text(row, "by"),
                                (String) row.get("target"),
                                text(row, "content"),
                                (String) row.get("evidence"),
                                (String) row.get("reviewNote")));
    }

    /** The batches that match {@code filter}, newest first. */
    public List<Batch> activity(ActivityFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("source", filter.source());
        params.put("batch", filter.batch());
        params.put("since", filter.since());
        return read(
                ACTIVITY,
                params,
                row ->
                        new Batch(
                                text(row, "batch"),
                                text(row, "at"),
                                KnowledgeQueriesRows.strings(row.get("sources")).stream()
                                        .sorted()
                                        .toList(),
                                count(row, "opened"),
                                count(row, "closed"),
                                count(row, "revised"),
                                KnowledgeQueriesRows.strings(row.get("entities")).stream()
                                        .sorted()
                                        .limit(ENTITIES_SHOWN)
                                        .toList()));
    }

    /** The secrets whose id starts with {@code prefix}, by id; never a value. */
    public List<SecretEntry> secrets(String prefix) {
        return read(
                SECRETS,
                Map.of("prefix", prefix),
                row ->
                        new SecretEntry(
                                text(row, "id"),
                                text(row, "name"),
                                text(row, "env"),
                                Boolean.TRUE.equals(row.get("hasValue"))));
    }

    private <T> List<T> read(
            String query, Map<String, Object> params, Function<Map<String, Object>, T> mapper) {
        return store.read(tx -> KnowledgeQueries.rows(tx, query, params, mapper));
    }

    // An imported record is taken as it is (invariant 11), so a missing property reads as empty.
    private static String text(Map<String, Object> row, String column) {
        return Objects.toString(row.get(column), "");
    }

    private static int count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }
}
