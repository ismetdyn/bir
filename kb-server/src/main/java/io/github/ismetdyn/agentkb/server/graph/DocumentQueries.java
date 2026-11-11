package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.BlobPaths;
import io.github.ismetdyn.agentkb.core.render.DocumentView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The read side of Documents (SPEC §6.7) for kb_read and {@code GET /api/v1/documents}: their
 * metadata, ABOUT targets and the facts that came from them (FROM). The blob path is made from the
 * sha256 (BlobPaths), never taken from the node, which a snapshot from another machine may carry,
 * and handed out only for a regular file reached through no link.
 */
@Component
public class DocumentQueries {

    private static final String DOCUMENT_FIELDS =
            """
            RETURN d.id AS id, d.name AS name, d.sha256 AS sha256, d.mime AS mime, d.size AS size,
                   d.origin AS origin, d.added_at AS addedAt,
                   [(d)-[:ABOUT]->(t) | t.id] AS about
            """;
    private static final String DOCUMENT = "MATCH (d:Document {id: $id}) " + DOCUMENT_FIELDS;
    // Neo4j sorts null highest, so a Document without added_at (a snapshot row taken as it is)
    // would come first; '' puts it last. RETURN does not keep the order of WITH, so it is restated.
    private static final String DOCUMENTS =
            "MATCH (d:Document) WITH d ORDER BY coalesce(d.added_at, '') DESC, d.id LIMIT $limit "
                    + DOCUMENT_FIELDS
                    + "ORDER BY coalesce(addedAt, '') DESC, id";
    private static final String FACTS =
            """
            MATCH (:Document {id: $id})<-[:FROM]-(f:Fact)
            OPTIONAL MATCH (e:Entity)-[:HAS_FACT]->(f)
            RETURN e.id AS entity,
                   f {.id, .key, .value, .env, .source, .valid_from, .observed_at, .valid_to,
                      .status} AS fact,
                   [(f)-[:TARGET]->(t) | t.id][0] AS target
            ORDER BY entity, f.key, f.env, f.valid_from, f.id
            """;

    private static final String NOT_FROM =
            """
            MATCH (f:Fact) WHERE f.id IN $facts AND NOT (f)-[:FROM]->(:Document {id: $id})
            RETURN count(f) AS n
            """;

    private final EmbeddedNeo4jStore store;
    private final String repo;

    public DocumentQueries(EmbeddedNeo4jStore store, @Value("${agentkb.repo:}") String repo) {
        this.store = store;
        this.repo = repo;
    }

    /** The Document with exactly this id. */
    public Optional<DocumentView> document(String id) {
        return store
                .read(tx -> ReadRows.in(tx).rows(DOCUMENT, Map.of("id", id), this::view))
                .stream()
                .findFirst();
    }

    /** At most {@code limit} Documents, newest first. */
    public List<DocumentView> documents(int limit) {
        return store.read(
                tx -> ReadRows.in(tx).rows(DOCUMENTS, Map.of("limit", limit), this::view));
    }

    /** The facts with a FROM edge to the Document, current and closed alike (invariant 12). */
    public List<DocumentView.Derived> facts(String id) {
        return store.read(
                tx ->
                        ReadRows.in(tx)
                                .rows(
                                        FACTS,
                                        Map.of("id", id),
                                        row ->
                                                new DocumentView.Derived(
                                                        (String) row.get("entity"),
                                                        KnowledgeQueriesRows.factView(row))));
    }

    /** How many of {@code facts} (ids) have no FROM edge to the Document {@code id}. */
    public long factsNotFrom(String id, List<String> facts) {
        return store.read(
                tx ->
                        ReadRows.in(tx)
                                .rows(
                                        NOT_FROM,
                                        Map.of("id", id, "facts", facts),
                                        row -> ((Number) row.get("n")).longValue())
                                .getFirst());
    }

    private DocumentView view(Map<String, Object> row) {
        String sha256 = row.get("sha256") instanceof String sha ? sha : null;
        List<String> about = new ArrayList<>(KnowledgeQueriesRows.strings(row.get("about")));
        about.sort(null);
        return new DocumentView(
                (String) row.get("id"),
                text(row.get("name")),
                sha256,
                text(row.get("mime")),
                row.get("size") instanceof Number size ? size.longValue() : null,
                text(row.get("origin")),
                text(row.get("addedAt")),
                path(sha256),
                about);
    }

    /**
     * CP4 S1: the agent may open this path, and blobs/ fills from kb sync, so only a regular file
     * in real directories is handed out; a link or a missing blob gives none.
     */
    private @Nullable String path(@Nullable String sha256) {
        if (sha256 == null || repo.isBlank()) {
            return null;
        }
        return BlobPaths.regularFile(Path.of(repo), sha256)
                .map(blob -> blob.toAbsolutePath().toString())
                .orElse(null);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String text ? text : null;
    }
}
