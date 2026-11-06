package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotMerge.Tie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Result;

/**
 * The graph after the merge of {@code kb sync}, in the caller's one transaction (ADR-0022 decisions
 * 41 and 43). Each tie opens a {@code conflict} signal about its record. A single-valued slot that
 * two machines each gave a new value keeps both (invariant 11) and is made consistent: a value of
 * lower trust than the strongest waits as a conflict; of equal trust the newer value stays current,
 * the older closes at its valid_from with SUPERSEDES, and a conflict signal opens. Every machine
 * settles the same merged rows the same way: the SUPERSEDES id is derived from the two facts, so
 * two machines that settle one slot on their own open the same edge row and the next merge keeps
 * one.
 */
final class SyncSettle {

    private static final String HOLDERS =
            """
            MATCH (e)-[:HAS_FACT]->(f:Fact)
            WHERE f.valid_to IS NULL AND coalesce(f.status, '') <> 'conflict'
            WITH e, f.key AS key, coalesce(f.env, 'default') AS env, collect(f) AS holders
            WHERE size(holders) > 1
            RETURN e AS entity, key, holders
            """;
    private static final RelationshipType SUPERSEDES = RelationshipType.withName("SUPERSEDES");
    private static final String RECORD = "MATCH (n {id: $id}) WHERE NOT n:Signal RETURN n LIMIT 1";
    private static final Comparator<Fact> STRONGEST_FIRST =
            Comparator.comparingInt((Fact fact) -> fact.source().trust())
                    .thenComparing(Fact::validFrom)
                    .thenComparing(Fact::id)
                    .reversed();

    private SyncSettle() {}

    /**
     * @return the conflicts it found: ties plus disputed values
     */
    static int settle(GraphWrite graph, List<Tie> ties, Schema schema) {
        String now = Times.format(Instant.now());
        int conflicts = 0;
        for (Tie tie : ties) {
            Node about = record(graph, tie.about());
            if (about != null) {
                tieSignal(graph, tie, about, now);
                conflicts++;
            }
        }
        Set<String> single = singleValued(schema);
        for (Map<String, Object> slot : holders(graph)) {
            if (single.contains((String) slot.get("key"))) {
                conflicts += settle(graph, (Node) slot.get("entity"), facts(slot));
            }
        }
        return conflicts;
    }

    private static int settle(GraphWrite graph, Node entity, List<Node> holders) {
        ConflictSignals signals = new ConflictSignals(graph);
        List<Node> sorted = new ArrayList<>(holders);
        sorted.sort(Comparator.comparing(SyncSettle::fact, STRONGEST_FIRST));
        Node kept = sorted.getFirst();
        Fact current = fact(kept);
        for (Node other : sorted.subList(1, sorted.size())) {
            Fact disputed = fact(other);
            if (disputed.source().trust() < current.source().trust()) {
                graph.set(other, "status", "conflict");
                signals.raise(entity, disputed.withStatus(FactStatus.CONFLICT), current);
            } else {
                graph.set(other, "valid_to", Times.format(current.validFrom()));
                supersede(graph, kept, other, current);
                signals.raise(entity, disputed, current);
            }
        }
        return sorted.size() - 1;
    }

    /** A merge can bring back the older fact open next to the edge it already has: no second. */
    private static void supersede(GraphWrite graph, Node kept, Node other, Fact current) {
        String id =
                Ulid.derived(
                        current.validFrom(),
                        SUPERSEDES.name()
                                + " "
                                + current.id()
                                + " "
                                + other.getProperty(GraphWrite.ID));
        try (ResourceIterable<Relationship> edges =
                kept.getRelationships(Direction.OUTGOING, SUPERSEDES)) {
            if (edges.stream().anyMatch(edge -> id.equals(edge.getProperty(GraphWrite.ID, null)))) {
                return;
            }
        }
        graph.edge(kept, SUPERSEDES.name(), other, Map.of()).setProperty(GraphWrite.ID, id);
    }

    // Values of an id here come from snapshot rows; a signal names only the id (invariant 2).
    private static void tieSignal(GraphWrite graph, Tie tie, Node about, String now) {
        Node created =
                SignalWrite.raise(
                        graph,
                        "conflict",
                        "sync:" + tie.id(),
                        "record "
                                + tie.id()
                                + " changed on two machines at the same updated_at; sync kept"
                                + " the larger row, the other version stays in git history",
                        now);
        if (created != null) {
            graph.localEdge(created, "ABOUT", about);
        }
    }

    /** Keys that are neither multi nor text (WriteCheck); an unknown key is left alone. */
    private static Set<String> singleValued(Schema schema) {
        return schema.factKeys().stream()
                .filter(key -> !"true".equals(key.rules().get("multi")))
                .filter(key -> !"text".equals(key.rules().get("kind")))
                .map(Schema.FactKey::key)
                .collect(Collectors.toSet());
    }

    private static List<Map<String, Object>> holders(GraphWrite graph) {
        try (Result result = graph.tx.execute(HOLDERS)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            result.forEachRemaining(rows::add);
            return rows;
        }
    }

    private static List<Node> facts(Map<String, Object> slot) {
        return ((List<?>) slot.get("holders")).stream().map(Node.class::cast).toList();
    }

    private static @Nullable Node record(GraphWrite graph, String id) {
        try (Result result = graph.tx.execute(RECORD, Map.of("id", id))) {
            return result.hasNext() ? (Node) result.next().get("n") : null;
        }
    }

    private static Fact fact(Node node) {
        return FactWrites.fact(node, (String) node.getProperty("value"));
    }
}
