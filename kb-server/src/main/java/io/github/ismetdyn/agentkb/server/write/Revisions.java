package io.github.ismetdyn.agentkb.server.write;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * ADR-0025 decision 15: before a user edit changes fields of a record in place (entity aliases,
 * summary, status; proposal status, review_note) it keeps their values in a {@code Revision} node
 * of its batch that REVISES the record, so {@code kb revert --batch} can put them back (Wikidata
 * revisions). {@code fields} names what the edit changed; a named field the Revision lacks had no
 * value and is removed again. Revisions go into the snapshot like any record (invariant 8) and are
 * not in the fulltext index, so they are no search hits. A value stored on a Secret is masked in
 * what a Revision keeps, so it does not reach the snapshot through it (invariant 2, CP3 S3).
 */
final class Revisions {

    static final String LABEL = "Revision";
    static final String REVISES = "REVISES";
    private static final String FIELDS = "fields";
    // CP3 S2: the fields an edit changes, per label of the record; a Revision from another machine
    // that names any other field (secret_value, valid_from...) is not restored.
    private static final Map<String, Set<String>> RESTORABLE =
            Map.of(
                    "Entity", Set.of("aliases", "summary", "status"),
                    "Proposal", Set.of("status", "review_note"));
    private static final String RESTORE =
            """
            MATCH (rv:Revision)-[:REVISES]->(record)
            WHERE elementId(rv) IN $nodes
            RETURN rv, record
            """;

    private Revisions() {}

    /**
     * Keeps the current values of {@code fields} of {@code record}; call it before changing them.
     * The field names are the record's own and never id, batch, episode, updated_at or fields.
     */
    static void keep(GraphWrite graph, Node record, String... fields) {
        List<String> stored = SecretWrite.storedValues(graph.tx);
        // An alias holds a stored value in its normalized form (EntityEdits).
        List<String> masked =
                Stream.concat(stored.stream(), EntityEdits.normalized(stored).stream())
                        .distinct()
                        .toList();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELDS, fields);
        for (String field : fields) {
            if (record.hasProperty(field)) {
                props.put(field, masked(record.getProperty(field), masked));
            }
        }
        Node revision = graph.node(Ulid.next(), props, LABEL);
        graph.edge(revision, REVISES, record, Map.of());
    }

    private static Object masked(Object value, List<String> stored) {
        return switch (value) {
            case String text -> SecretWrite.maskStoredValues(stored, text, WriteService.MASK);
            case String[] texts ->
                    Arrays.stream(texts)
                            .map(
                                    text ->
                                            SecretWrite.maskStoredValues(
                                                    stored, text, WriteService.MASK))
                            .toArray(String[]::new);
            default -> value;
        };
    }

    /**
     * Puts back what the Revisions among {@code nodes} kept, on the records they revise; the caller
     * then deletes them with the rest of the batch. One batch revises a record once.
     *
     * @param now {@code Times.format} of the revert, the updated_at of what it restores
     * @throws WriteRejectedException before any change if a Revision names a field no edit of its
     *     record changes
     */
    static void restore(Transaction tx, List<String> nodes, String now) {
        List<Revised> revised = new ArrayList<>();
        try (Result rows = tx.execute(RESTORE, Map.of("nodes", nodes))) {
            rows.forEachRemaining(
                    row ->
                            revised.add(
                                    new Revised((Node) row.get("rv"), (Node) row.get("record"))));
        }
        for (Revised pair : revised) {
            if (!isRestorable(pair.revision(), pair.record())) {
                throw new WriteRejectedException(
                        "a revision of this batch names a field no edit changes",
                        "Nothing was reverted. kb revert puts back only entity aliases, summary"
                                + " and status and proposal status and review_note; this"
                                + " revision did not come from a kb edit.");
            }
        }
        revised.forEach(pair -> restore(pair.revision(), pair.record(), now));
    }

    private record Revised(Node revision, Node record) {}

    private static boolean isRestorable(Node revision, Node record) {
        if (!(revision.getProperty(FIELDS, null) instanceof String[] fields)) {
            return false;
        }
        return RESTORABLE.entrySet().stream()
                .anyMatch(
                        allowed ->
                                record.hasLabel(Label.label(allowed.getKey()))
                                        && allowed.getValue().containsAll(Arrays.asList(fields)));
    }

    private static void restore(Node revision, Node record, String now) {
        for (String field : (String[]) revision.getProperty(FIELDS)) {
            if (revision.hasProperty(field)) {
                record.setProperty(field, revision.getProperty(field));
            } else {
                record.removeProperty(field);
            }
        }
        record.setProperty(GraphWrite.UPDATED_AT, now);
    }
}
