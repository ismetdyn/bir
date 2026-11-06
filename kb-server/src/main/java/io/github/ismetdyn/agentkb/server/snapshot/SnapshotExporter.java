package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Edge;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;

/**
 * Reads the graph as a snapshot (invariant 8): no Signal node, no edge touching one, no {@code
 * secret_value}. Runs under the writer lock so nodes and edges come from one state.
 */
public class SnapshotExporter {

    private static final String ID = "id";
    private static final String SECRET_VALUE = "secret_value";
    private static final Label SIGNAL = Label.label("Signal");
    // The seed's label order: Entity first, then the type label (types.yaml).
    private static final String ENTITY = "Entity";

    private final EmbeddedNeo4jStore store;

    public SnapshotExporter(EmbeddedNeo4jStore store) {
        this.store = store;
    }

    public Snapshot export() {
        return store.readUnderWriteLock(tx -> new Snapshot(nodes(tx), edges(tx)));
    }

    private static List<Node> nodes(Transaction tx) {
        List<Node> nodes = new ArrayList<>();
        try (ResourceIterable<org.neo4j.graphdb.Node> all = tx.getAllNodes()) {
            for (org.neo4j.graphdb.Node node : all) {
                if (!node.hasLabel(SIGNAL)) {
                    nodes.add(new Node(idOf(node), labels(node), props(node)));
                }
            }
        }
        // In id order like the files (invariant 8): a restore keeps user-set Secrets in place, so
        // the graph's own order differs from the snapshot it restored.
        nodes.sort(Comparator.comparing(Node::id));
        return nodes;
    }

    private static List<Edge> edges(Transaction tx) {
        List<Edge> edges = new ArrayList<>();
        try (ResourceIterable<Relationship> all = tx.getAllRelationships()) {
            for (Relationship edge : all) {
                if (!edge.getStartNode().hasLabel(SIGNAL) && !edge.getEndNode().hasLabel(SIGNAL)) {
                    edges.add(
                            new Edge(
                                    idOf(edge),
                                    edge.getType().name(),
                                    idOf(edge.getStartNode()),
                                    idOf(edge.getEndNode()),
                                    props(edge)));
                }
            }
        }
        edges.sort(Comparator.comparing(Edge::id));
        return edges;
    }

    private static List<String> labels(org.neo4j.graphdb.Node node) {
        List<String> labels = new ArrayList<>();
        node.getLabels().forEach(label -> labels.add(label.name()));
        labels.sort(
                Comparator.comparing((String label) -> !ENTITY.equals(label))
                        .thenComparing(Comparator.naturalOrder()));
        return labels;
    }

    private static String idOf(Entity record) {
        if (record.getProperty(ID, null) instanceof String id) {
            return id;
        }
        throw new IllegalStateException("record without id: " + record.getElementId());
    }

    private static Map<String, Object> props(Entity record) {
        Map<String, Object> props = new HashMap<>();
        record.getAllProperties()
                .forEach(
                        (key, value) -> {
                            if (!ID.equals(key) && !SECRET_VALUE.equals(key)) {
                                props.put(key, value.getClass().isArray() ? list(value) : value);
                            }
                        });
        return props;
    }

    private static List<Object> list(Object array) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < Array.getLength(array); i++) {
            list.add(Array.get(array, i));
        }
        return list;
    }
}
