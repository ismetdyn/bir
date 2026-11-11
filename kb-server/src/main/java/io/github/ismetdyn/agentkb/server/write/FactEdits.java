package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import io.github.ismetdyn.agentkb.server.write.GraphEdits.Resolution;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;

/**
 * The user's closes and conflict resolutions (ADR-0025 decisions 10-11). A close only writes
 * valid_to and closed_batch (closeByEdit); accept-new goes through the write path as a user write
 * (FactWrites), whose closes get closed_batch too. Nothing is deleted and no valid_from moves
 * (invariant 5).
 */
final class FactEdits {

    private static final Label FACT = Label.label("Fact");
    private static final Label ENTITY = Label.label("Entity");
    private static final RelationshipType HAS_FACT = RelationshipType.withName("HAS_FACT");
    private static final RelationshipType TARGET = RelationshipType.withName("TARGET");
    private static final String NO_FACT_HINT =
            "Give the id of an open fact as kb read <entity> lists it; a closed fact stays closed.";
    private static final String NO_RELATION_HINT =
            "Give the id of an open relation between two entities as kb read <entity> lists it.";

    private FactEdits() {}

    static void closeFact(GraphWrite graph, String id) {
        closeByEdit(graph, openFact(graph, id));
    }

    static void closeRelation(GraphWrite graph, String id) {
        Relationship relation = openRelation(graph, id);
        if (relation == null) {
            throw new WriteRejectedException("no open relation with this id", NO_RELATION_HINT);
        }
        closeByEdit(graph, relation);
    }

    static void resolve(GraphWrite graph, RepoSchema schema, String id, Resolution resolution) {
        Node waiting = openFact(graph, id);
        if (!"conflict".equals(waiting.getProperty("status", null))) {
            throw new WriteRejectedException(
                    "the fact waits for nothing",
                    "Only a value waiting as a conflict is resolved; kb read <entity> marks it.");
        }
        if (resolution == Resolution.KEEP_OLD) {
            closeByEdit(graph, waiting);
        } else {
            acceptNew(graph, schema.load(), waiting);
        }
    }

    /**
     * The waiting value written again as the user's (ADR-0022 decision 23): the waiting and the
     * current fact close, one new current fact opens with the waiting valid_from.
     */
    private static void acceptNew(GraphWrite graph, Schema schema, Node waiting) {
        Fact pending = FactWrites.fact(waiting, (String) waiting.getProperty("value"));
        Optional<FactKey> key =
                schema.factKeys().stream()
                        .filter(known -> known.key().equals(pending.key()))
                        .findFirst();
        boolean multi = key.map(FactKey::multi).orElse(false);
        boolean caseInsensitive = key.map(WriteCheck::isCaseInsensitive).orElse(false);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Fact incoming =
                new Fact(
                        Ulid.next(),
                        pending.key(),
                        pending.value(),
                        pending.env(),
                        Source.USER,
                        now,
                        now,
                        null,
                        FactStatus.OK,
                        null,
                        graph.batch);
        CheckedFact input =
                new CheckedFact(
                        pending.key(),
                        pending.value(),
                        pending.env(),
                        null,
                        multi,
                        caseInsensitive,
                        Source.USER);
        Relationship target = waiting.getSingleRelationship(TARGET, Direction.OUTGOING);
        Node entity = waiting.getSingleRelationship(HAS_FACT, Direction.INCOMING).getStartNode();
        FactWrites facts = new FactWrites(graph);
        facts.write(entity, input, incoming, target == null ? null : target.getEndNode());
        for (String closed : facts.closed) {
            graph.set(
                    graph.tx.findNode(FACT, GraphWrite.ID, closed),
                    GraphWrite.CLOSED_BATCH,
                    graph.batch);
        }
    }

    /**
     * A close by a user edit (decision 10): valid_to at this edit, never before valid_from, and
     * closed_batch, so {@code kb revert} reopens it and WritePolicy knows the user closed it
     * (decision 16). The batch that opened the record stays.
     */
    private static void closeByEdit(GraphWrite graph, Entity record) {
        String now = Times.format(Instant.now());
        String from = (String) record.getProperty(GraphWrite.VALID_FROM, now);
        graph.set(record, GraphWrite.VALID_TO, from.compareTo(now) > 0 ? from : now);
        graph.set(record, GraphWrite.CLOSED_BATCH, graph.batch);
    }

    private static Node openFact(GraphWrite graph, @Nullable String id) {
        Node fact = WriteCheck.isBlank(id) ? null : graph.tx.findNode(FACT, GraphWrite.ID, id);
        if (fact == null || fact.hasProperty(GraphWrite.VALID_TO)) {
            throw new WriteRejectedException("no open fact with this id", NO_FACT_HINT);
        }
        return fact;
    }

    /**
     * A vocabulary relation (relations.yaml) between two entities, by its edge id; null if none.
     */
    private static @Nullable Relationship openRelation(GraphWrite graph, @Nullable String id) {
        if (WriteCheck.isBlank(id)) {
            return null;
        }
        for (RelationshipType type : graph.tx.getAllRelationshipTypesInUse()) {
            if (Schema.FIXED_EDGE_TYPES.contains(type.name())) {
                continue;
            }
            try (ResourceIterator<Relationship> edges =
                    graph.tx.findRelationships(type, GraphWrite.ID, id)) {
                while (edges.hasNext()) {
                    Relationship edge = edges.next();
                    if (!edge.hasProperty(GraphWrite.VALID_TO)
                            && edge.getStartNode().hasLabel(ENTITY)
                            && edge.getEndNode().hasLabel(ENTITY)) {
                        return edge;
                    }
                }
            }
        }
        return null;
    }
}
