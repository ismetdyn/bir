package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;

/**
 * The records of one write transaction. Everything the write opens carries its batch, so {@code kb
 * revert --batch} finds it (ARCHITECTURE §5); a change to an existing record never does (ADR-0022
 * decision 10). Every record it touches gets the technical updated_at (SPEC §6.1). Signals and
 * their edges are local and outside any batch (ADR-0022 decision 7).
 */
final class GraphWrite {

    static final String ID = "id";
    static final String BATCH = "batch";
    static final String UPDATED_AT = "updated_at";

    final Transaction tx;
    final String batch;
    private final String updatedAt;

    GraphWrite(Transaction tx, String batch, Instant now) {
        this.tx = tx;
        this.batch = batch;
        this.updatedAt = Times.format(now);
    }

    Node node(String id, Map<String, Object> props, String... labels) {
        return created(id, props, batch, labels);
    }

    Node localNode(String id, Map<String, Object> props, String... labels) {
        return created(id, props, null, labels);
    }

    Relationship edge(Node from, String type, Node to, Map<String, Object> props) {
        return stamped(
                from.createRelationshipTo(to, RelationshipType.withName(type)), props, batch);
    }

    Relationship localEdge(Node from, String type, Node to) {
        return stamped(
                from.createRelationshipTo(to, RelationshipType.withName(type)), Map.of(), null);
    }

    /** Changes one property of an existing record; its batch stays the one that opened it. */
    void set(Entity record, String key, Object value) {
        record.setProperty(key, value);
        record.setProperty(UPDATED_AT, updatedAt);
    }

    private Node created(
            String id, Map<String, Object> props, @Nullable String withBatch, String... labels) {
        Label[] all = new Label[labels.length];
        for (int i = 0; i < labels.length; i++) {
            all[i] = Label.label(labels[i]);
        }
        Node node = tx.createNode(all);
        node.setProperty(ID, id);
        return stamp(node, props, withBatch);
    }

    private Relationship stamped(
            Relationship edge, Map<String, Object> props, @Nullable String withBatch) {
        edge.setProperty(ID, Ulid.next());
        return stamp(edge, props, withBatch);
    }

    private <T extends Entity> T stamp(
            T record, Map<String, Object> props, @Nullable String withBatch) {
        props.forEach(record::setProperty);
        if (withBatch != null) {
            record.setProperty(BATCH, withBatch);
        }
        record.setProperty(UPDATED_AT, updatedAt);
        return record;
    }
}
