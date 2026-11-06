package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;

/**
 * {@link GraphMaintenance#replaceAll} in its one transaction (SPEC §10, ARCHITECTURE §8, ADR-0022
 * decision 38). Local data is set aside first and put back after the snapshot is loaded: every
 * Signal and every Secret the snapshot lacks stay as they are, the value of a Secret the snapshot
 * has moves onto its new node. An edge of a kept record to a record that goes is put back when the
 * snapshot has that record again; otherwise it drops. Rows are loaded as they are (invariant 11).
 */
final class RecordRestore {

    private static final Label SIGNAL = Label.label("Signal");
    private static final Label SECRET = Label.label("Secret");
    private static final String ID = GraphWrite.ID;
    private static final String DELETE =
            "MATCH (n) WHERE NOT n:Signal AND NOT (n:Secret AND n.id IN $kept) DETACH DELETE n";

    /** An edge between a kept node and one that goes, by the id of that other node. */
    private record KeptEdge(
            Node kept,
            boolean outgoing,
            String type,
            String otherId,
            Map<String, Object> props,
            boolean ofSignal) {}

    private RecordRestore() {}

    static RestoreResult replace(Transaction tx, Snapshot snapshot) {
        Set<String> snapshotSecrets =
                snapshot.nodes().stream()
                        .filter(node -> node.labels().contains(SECRET.name()))
                        .map(node -> node.id())
                        .collect(Collectors.toSet());
        Set<String> keptIds = new HashSet<>();
        Map<String, Object> values = new HashMap<>();
        sortSecrets(tx, snapshotSecrets, keptIds, values);
        List<Node> kept = new ArrayList<>();
        try (ResourceIterator<Node> signals = tx.findNodes(SIGNAL)) {
            signals.forEachRemaining(kept::add);
        }
        int keptSignals = kept.size();
        keptIds.forEach(id -> kept.add(tx.findNode(SECRET, ID, id)));
        List<KeptEdge> edges = edgesToRecordsThatGo(kept, keptIds);
        tx.execute(DELETE, Map.of("kept", List.copyOf(keptIds))).close();
        Map<String, Node> created = RecordImport.create(tx, snapshot);
        values.forEach((id, value) -> created.get(id).setProperty(SecretWrite.SECRET_VALUE, value));
        return new RestoreResult(
                snapshot.nodes().size(),
                snapshot.edges().size(),
                keptIds.size(),
                keptSignals,
                putBack(edges, created),
                RecordImport.secretValues(snapshot));
    }

    /**
     * A Secret the snapshot lacks is kept ({@code keptIds}); the value of one it has goes into
     * {@code values}, to be set on the new node.
     */
    private static void sortSecrets(
            Transaction tx,
            Set<String> snapshotSecrets,
            Set<String> keptIds,
            Map<String, Object> values) {
        try (ResourceIterator<Node> secrets = tx.findNodes(SECRET)) {
            while (secrets.hasNext()) {
                Node secret = secrets.next();
                if (!snapshotSecrets.contains(id(secret))) {
                    keptIds.add(id(secret));
                } else if (secret.hasProperty(SecretWrite.SECRET_VALUE)) {
                    values.put(id(secret), secret.getProperty(SecretWrite.SECRET_VALUE));
                }
            }
        }
    }

    /** The edges of {@code kept} nodes (signals and {@code keptIds} secrets) to nodes that go. */
    private static List<KeptEdge> edgesToRecordsThatGo(List<Node> kept, Set<String> keptIds) {
        List<KeptEdge> edges = new ArrayList<>();
        for (Node node : kept) {
            try (ResourceIterable<Relationship> all = node.getRelationships()) {
                for (Relationship edge : all) {
                    Node other = edge.getOtherNode(node);
                    if (!isKept(other, keptIds)) {
                        edges.add(
                                new KeptEdge(
                                        node,
                                        edge.getStartNode().equals(node),
                                        edge.getType().name(),
                                        id(other),
                                        edge.getAllProperties(),
                                        node.hasLabel(SIGNAL)));
                    }
                }
            }
        }
        return edges;
    }

    private static boolean isKept(Node node, Set<String> keptIds) {
        return node.hasLabel(SIGNAL) || node.hasLabel(SECRET) && keptIds.contains(id(node));
    }

    /**
     * Puts each edge back onto the new node of its other end.
     *
     * @return the signal edges dropped because the snapshot lacks their other end
     */
    private static int putBack(List<KeptEdge> edges, Map<String, Node> created) {
        int dropped = 0;
        for (KeptEdge edge : edges) {
            Node other = created.get(edge.otherId());
            if (other == null) {
                dropped += edge.ofSignal() ? 1 : 0;
                continue;
            }
            RelationshipType type = RelationshipType.withName(edge.type());
            Relationship restored =
                    edge.outgoing()
                            ? edge.kept().createRelationshipTo(other, type)
                            : other.createRelationshipTo(edge.kept(), type);
            edge.props().forEach(restored::setProperty);
        }
        return dropped;
    }

    private static String id(Node node) {
        return (String) node.getProperty(ID, null);
    }
}
