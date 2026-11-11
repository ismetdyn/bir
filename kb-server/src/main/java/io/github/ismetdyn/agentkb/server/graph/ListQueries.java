package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.Ids;
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

    /**
     * One record a batch opened, closed or revised: {@code kind} its label (Entity for an entity,
     * Relation for an edge between entities, the revised record's for a revision); {@code key} the
     * fact key, relation or entity type, a secret's name or the fields a revision changed; {@code
     * value} the fact value, relation target or text of the record, never a secret value.
     */
    public record Change(
            String change,
            String kind,
            @Nullable String id,
            @Nullable String entity,
            @Nullable String key,
            @Nullable String env,
            @Nullable String value,
            String source) {}

    /** An entity as a browse list shows it; {@code type} is its graph label. */
    public record EntityEntry(
            String id, String type, String status, @Nullable String summary, String updatedAt) {}

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
    // The columns that name node %1$s of a batch (Change). A Secret's text is never read: its
    // value is secret_value, which no column names (invariant 2).
    private static final String NODE_COLUMNS =
            """
            %1$s.id AS record, CASE WHEN %1$s:Entity THEN 'Entity' ELSE head(labels(%1$s)) END AS kind,
            CASE WHEN %1$s:Entity THEN %2$s
                 ELSE coalesce(%1$s.key, %1$s.name, %1$s.kind) END AS key,
            %1$s.env AS env,
            CASE WHEN %1$s:Secret THEN null
                 ELSE coalesce(%1$s.value, %1$s.summary, %1$s.content, %1$s.rule, %1$s.title)
            END AS value""";
    private static final String EDGE_COLUMNS =
            "r.id AS record, 'Relation' AS kind, type(r) AS key, r.env AS env, b.id AS value";
    // Every record of a batch, a row per way it changed it: what it opened (batch), what it closed,
    // and what it revised (Revision, ADR-0025 decision 15). A close is a user edit's closed_batch
    // (decision 10) or a fact the batch superseded (SUPERSEDES from one of its records), as
    // WriteResult and ScanResult count them; accept-new does both for one fact. Relations count
    // only between entities, the vocabulary ones; HAS_FACT and the like belong to their node.
    private static final String BATCH_RECORDS =
            """
              MATCH (n) WHERE n.batch IS NOT NULL AND NOT n:Revision
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT|HAS_SECRET]->(n)
              RETURN n.batch AS batch, coalesce(n.source, n.by) AS source, n.updated_at AS at,
                     'opened' AS change, elementId(n) AS rec,
                     coalesce(owner.id, CASE WHEN n:Entity THEN n.id END) AS entity, %1$s
              UNION ALL
              MATCH (a:Entity)-[r]->(b:Entity) WHERE r.batch IS NOT NULL
              RETURN r.batch AS batch, r.source AS source, r.updated_at AS at,
                     'opened' AS change, elementId(r) AS rec, a.id AS entity, %3$s
              UNION ALL
              MATCH (n) WHERE n.closed_batch IS NOT NULL
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT]->(n)
              RETURN n.closed_batch AS batch, 'user' AS source, n.valid_to AS at,
                     'closed' AS change, elementId(n) AS rec, owner.id AS entity, %1$s
              UNION ALL
              MATCH (a:Entity)-[r]->(b:Entity) WHERE r.closed_batch IS NOT NULL
              RETURN r.closed_batch AS batch, 'user' AS source, r.valid_to AS at,
                     'closed' AS change, elementId(r) AS rec, a.id AS entity, %3$s
              UNION ALL
              MATCH (n)-[:SUPERSEDES]->(old)
              WHERE n.batch IS NOT NULL AND old.valid_to IS NOT NULL
                AND (old.batch IS NULL OR old.batch <> n.batch)
              OPTIONAL MATCH (owner:Entity)-[:HAS_FACT]->(old)
              RETURN n.batch AS batch, n.source AS source, old.valid_to AS at,
                     'closed' AS change, elementId(old) AS rec, owner.id AS entity, %2$s
              UNION ALL
              MATCH (rv:Revision)-[:REVISES]->(x)
              RETURN rv.batch AS batch, 'user' AS source, rv.updated_at AS at,
                     'revised' AS change, elementId(rv) AS rec,
                     CASE WHEN x:Entity THEN x.id END AS entity, x.id AS record,
                     CASE WHEN x:Entity THEN 'Entity' ELSE head(labels(x)) END AS kind,
                     reduce(s = '', f IN coalesce(rv.fields, [])
                            | s + CASE WHEN s = '' THEN '' ELSE ', ' END + f) AS key,
                     null AS env, null AS value
            """
                    .formatted(
                            NODE_COLUMNS.formatted("n", KnowledgeQueries.TYPE_OF.formatted("n")),
                            NODE_COLUMNS.formatted(
                                    "old", KnowledgeQueries.TYPE_OF.formatted("old")),
                            EDGE_COLUMNS);
    // Each record counted once per way the batch changed it.
    private static final String ACTIVITY =
            """
            CALL () {
            %s}
            WITH batch, coalesce(max(at), '') AS at, collect(DISTINCT source) AS sources,
                 count(DISTINCT CASE WHEN change = 'opened' THEN rec END) AS opened,
                 count(DISTINCT CASE WHEN change = 'closed' THEN rec END) AS closed,
                 count(DISTINCT CASE WHEN change = 'revised' THEN rec END) AS revised,
                 collect(DISTINCT entity) AS entities
            WHERE ($batch IS NULL OR batch = $batch) AND ($since IS NULL OR at >= $since)
              AND ($source IS NULL OR $source IN sources)
            RETURN batch, at, sources, opened, closed, revised, entities
            ORDER BY at DESC, batch DESC
            """
                    .formatted(BATCH_RECORDS);
    // A record closed two ways (accept-new) is one row; its sources then differ only in name.
    private static final String CHANGES =
            """
            CALL () {
            %s}
            WITH * WHERE batch = $batch
            WITH change, rec, record, kind, entity, key, env, value, min(source) AS source
            RETURN change, record, kind, entity, key, env, value, source
            ORDER BY CASE change WHEN 'opened' THEN 0 WHEN 'closed' THEN 1 ELSE 2 END,
                     entity, kind, key, record
            """
                    .formatted(BATCH_RECORDS);
    // Archived entities last (invariant 12: ordered and marked, never left out).
    private static final String ENTITIES =
            """
            MATCH (e:Entity)
            RETURN e.id AS entity, %s AS type, e.status AS status, e.summary AS summary,
                   e.updated_at AS updatedAt
            ORDER BY CASE WHEN e.status = 'archived' THEN 1 ELSE 0 END, e.id
            """
                    .formatted(KnowledgeQueries.TYPE_OF.formatted("e"));
    private static final String SECRETS =
            """
            MATCH (s:Secret) WHERE s.id STARTS WITH $prefix
            RETURN s.id AS secret, coalesce(s.name, '') AS name, coalesce(s.env, 'default') AS env,
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

    /** What {@code batch} opened, closed and revised: opened first, then by entity and kind. */
    public List<Change> changes(String batch) {
        return read(
                CHANGES,
                Map.of("batch", batch),
                row ->
                        new Change(
                                text(row, "change"),
                                text(row, "kind"),
                                Objects.toString(row.get("record"), null),
                                Objects.toString(row.get("entity"), null),
                                Objects.toString(row.get("key"), null),
                                Objects.toString(row.get("env"), null),
                                Objects.toString(row.get("value"), null),
                                text(row, "source")));
    }

    /**
     * Every entity, archived ones last, then by id; of one type if {@code type} names its label or
     * key in any case, as search filters (KnowledgeQueries#search).
     */
    public List<EntityEntry> entities(@Nullable String type) {
        return read(
                        ENTITIES,
                        Map.of(),
                        row ->
                                new EntityEntry(
                                        text(row, "entity"),
                                        text(row, "type"),
                                        text(row, "status"),
                                        Objects.toString(row.get("summary"), null),
                                        text(row, "updatedAt")))
                .stream()
                .filter(e -> type == null || Ids.normalize(type).equals(Ids.normalize(e.type())))
                .toList();
    }

    /** The secrets whose id starts with {@code prefix}, by id; never a value. */
    public List<SecretEntry> secrets(String prefix) {
        return read(
                SECRETS,
                Map.of("prefix", prefix),
                row ->
                        new SecretEntry(
                                text(row, "secret"),
                                text(row, "name"),
                                text(row, "env"),
                                Boolean.TRUE.equals(row.get("hasValue"))));
    }

    private <T> List<T> read(
            String query, Map<String, Object> params, Function<Map<String, Object>, T> mapper) {
        return store.read(tx -> ReadRows.in(tx).rows(query, params, mapper));
    }

    // An imported record is taken as it is (invariant 11), so a missing property reads as empty.
    private static String text(Map<String, Object> row, String column) {
        return Objects.toString(row.get(column), "");
    }

    private static int count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }
}
