package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;

/** The rows of {@link WriteService#importRecords} as graph records, in its one transaction. */
final class RecordImport {

    private static final String ID = "id";

    private RecordImport() {}

    static Void create(Transaction tx, Snapshot snapshot) {
        Map<String, org.neo4j.graphdb.Node> created = new HashMap<>();
        for (Node node : snapshot.nodes()) {
            org.neo4j.graphdb.Node graphNode =
                    tx.createNode(node.labels().stream().map(Label::label).toArray(Label[]::new));
            graphNode.setProperty(ID, node.id());
            node.props()
                    .forEach(
                            (key, value) ->
                                    graphNode.setProperty(key, stored(node.id(), key, value)));
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
            edge.props()
                    .forEach(
                            (key, value) ->
                                    relationship.setProperty(key, stored(edge.id(), key, value)));
        }
        return null;
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
