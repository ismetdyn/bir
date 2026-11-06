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
 * revert --batch} finds it (ARCHITECTURE §5), and its episode when one is known (ADR-0023 decision
 * 2); a change to an existing record never does (ADR-0022 decision 10). Every record it touches
 * gets the technical updated_at (SPEC §6.1). Signals and their edges are local and outside any
 * batch (ADR-0022 decision 7).
 */
final class GraphWrite {

    static final String ID = "id";
    static final String BATCH = "batch";
    static final String EPISODE = "episode";
    static final String UPDATED_AT = "updated_at";
    static final String VALID_FROM = "valid_from";
    static final String VALID_TO = "valid_to";
    static final String CLOSED_BATCH = "closed_batch";

    final Transaction tx;
    final String batch;
    private final @Nullable String episode;
    // The props of an Episode this write opens with its first stamp, if the graph lacks it.
    private @Nullable Map<String, Object> newEpisode;
    private final String updatedAt;

    GraphWrite(Transaction tx, String batch, Instant now) {
        this(tx, batch, null, null, now);
    }

    /** {@code episode} is the id of an existing Episode (EpisodeWrite.known), or null. */
    GraphWrite(Transaction tx, String batch, @Nullable String episode, Instant now) {
        this(tx, batch, episode, null, now);
    }

    /**
     * With {@code newEpisode}, the Episode {@code episode} is opened with these props when the
     * first record is stamped and the graph does not hold it yet: a write that opens nothing, such
     * as the rescan of an unchanged repository, leaves no Episode behind.
     */
    GraphWrite(
            Transaction tx,
            String batch,
            @Nullable String episode,
            @Nullable Map<String, Object> newEpisode,
            Instant now) {
        this.tx = tx;
        this.batch = batch;
        this.episode = episode;
        this.newEpisode = newEpisode;
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

    /** An existing record this write takes over: it gets this batch and episode, as if opened. */
    void claim(Entity record) {
        record.setProperty(BATCH, batch);
        stampEpisode(record);
    }

    private void stampEpisode(Entity record) {
        if (episode == null) {
            return;
        }
        if (newEpisode != null) {
            Map<String, Object> props = newEpisode;
            // Looked up once per transaction; the Episode itself has no batch, like a Signal.
            newEpisode = null;
            if (tx.findNode(Label.label(EpisodeWrite.LABEL), ID, episode) == null) {
                created(episode, props, null, EpisodeWrite.LABEL);
            }
        }
        record.setProperty(EPISODE, episode);
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
            stampEpisode(record);
        }
        record.setProperty(UPDATED_AT, updatedAt);
        return record;
    }
}
