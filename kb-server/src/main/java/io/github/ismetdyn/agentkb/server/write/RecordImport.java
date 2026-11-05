package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;

/**
 * The rows of {@link WriteService#importRecords} and {@link WriteService#replaceAll} as graph
 * records, in the caller's one transaction.
 */
final class RecordImport {

    private static final String ID = "id";

    private RecordImport() {}

    /**
     * @return the created nodes by id
     */
    static Map<String, org.neo4j.graphdb.Node> create(Transaction tx, Snapshot snapshot) {
        Map<String, org.neo4j.graphdb.Node> created = new HashMap<>();
        for (Node node : snapshot.nodes()) {
            org.neo4j.graphdb.Node graphNode =
                    tx.createNode(node.labels().stream().map(Label::label).toArray(Label[]::new));
            graphNode.setProperty(ID, node.id());
            setProps(graphNode, node.id(), node.props());
            if (created.put(node.id(), graphNode) != null) {
                throw new IllegalArgumentException("duplicate node id " + node.id());
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (Edge edge : snapshot.edges()) {
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("duplicate edge id " + edge.id());
            }
            var relationship =
                    endpoint(created, edge, edge.from())
                            .createRelationshipTo(
                                    endpoint(created, edge, edge.to()),
                                    RelationshipType.withName(edge.type()));
            relationship.setProperty(ID, edge.id());
            setProps(relationship, edge.id(), edge.props());
        }
        return created;
    }

    /**
     * Copies a row's props, but never {@code id} (the row's own id is set) or a secret_value: a
     * snapshot is pulled from a remote, so a value in it is untrusted and must not become the one
     * {@code kb run} injects (invariant 2). SnapshotExporter never writes either.
     */
    private static void setProps(Entity record, String recordId, Map<String, Object> props) {
        props.forEach(
                (key, value) -> {
                    if (!ID.equals(key) && !isSecret(key)) {
                        record.setProperty(key, stored(recordId, key, value));
                    }
                });
    }

    /** The rows of {@code snapshot} that carry a secret_value key in any case. */
    static int secretValues(Snapshot snapshot) {
        return (int)
                Stream.concat(
                                snapshot.nodes().stream().map(Node::props),
                                snapshot.edges().stream().map(Edge::props))
                        .filter(props -> props.keySet().stream().anyMatch(RecordImport::isSecret))
                        .count();
    }

    private static boolean isSecret(String key) {
        return SecretWrite.SECRET_VALUE.equalsIgnoreCase(key);
    }

    private static org.neo4j.graphdb.Node endpoint(
            Map<String, org.neo4j.graphdb.Node> created, Edge edge, String nodeId) {
        org.neo4j.graphdb.Node node = created.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException(
                    "edge " + edge.id() + " points to missing node " + nodeId);
        }
        return node;
    }

    /**
     * JSON value to a Neo4j property value. Lists become typed arrays; an empty list is stored as
     * an empty string array. Maps, nulls and mixed lists have no property form, so they fail
     * instead of being dropped. The message names the record and key, never the value.
     */
    private static Object stored(String recordId, String key, Object value) {
        return switch (value) {
            case String text -> text;
            case Boolean bool -> bool;
            case Integer number -> number.longValue();
            case Long number -> number;
            case Double number -> number;
            case List<?> list when list.stream().allMatch(String.class::isInstance) ->
                    list.toArray(String[]::new);
            case List<?> list when list.stream().allMatch(RecordImport::isWhole) ->
                    list.stream().mapToLong(item -> ((Number) item).longValue()).toArray();
            case List<?> list when list.stream().allMatch(Double.class::isInstance) ->
                    list.stream().mapToDouble(item -> (Double) item).toArray();
            case null, default ->
                    throw new IllegalArgumentException(
                            "record " + recordId + ": property " + key + " has no graph form");
        };
    }

    private static boolean isWhole(Object item) {
        return item instanceof Integer || item instanceof Long;
    }
}
