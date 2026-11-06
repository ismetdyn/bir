package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotMerge;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * {@code kb revert --batch <id>} (ARCHITECTURE §5, ADR-0022 decisions 10 and 22-23): the one
 * deletion invariant 5 allows. A fact never changes in place, so a batch is undone by deleting
 * every node and edge it opened (they carry its batch) and reopening what it closed (what its nodes
 * point to with SUPERSEDES). Signals carry no batch and stay; an ABOUT edge of a signal to a
 * deleted record goes with it. A Secret the batch opened goes with its value; one the user set a
 * value on since carries the batch of that PUT and stays (SecretWrite.put), only this batch's
 * HAS_SECRET edge to it goes. The result counts the values deleted. Runs in the caller's one
 * transaction.
 *
 * <p>The revert leaves a {@code RevertedBatch {id: <batch>, at}} node (ADR-0022 decision 42). It
 * goes into the snapshot, so {@code kb sync} does not bring the batch back from another machine
 * (SnapshotMerge) and drops it there too.
 *
 * <p>No partial revert: when a later batch built on a record of this one (an edge of the later
 * batch touches it: a HAS_FACT onto its entity, a SUPERSEDES that closed its fact; or a conflict
 * value of the later batch waits next to an open fact of this batch), the whole revert is refused
 * and the hint names the later batch, which goes first.
 *
 * <p>The records are found through the batch index of each label and type (GraphSchema); a label or
 * type without one, such as an entity type label next to Entity, is scanned. A fact a rescan closed
 * without SUPERSEDES (ScanGraph) is not found and stays closed.
 */
final class BatchRevert {

    private static final String LATER =
            """
            MATCH (n)-[r]-()
            WHERE elementId(n) IN $nodes AND r.batch IS NOT NULL AND r.batch <> $batch
            RETURN r.batch AS later
            UNION
            MATCH (e)-[:HAS_FACT]->(f:Fact)
            WHERE elementId(f) IN $nodes AND f.valid_to IS NULL
              AND coalesce(f.status, '') <> 'conflict'
            MATCH (e)-[:HAS_FACT]->(c:Fact {status: 'conflict'})
            WHERE c.key = f.key AND c.env = f.env AND c.valid_to IS NULL
              AND c.batch IS NOT NULL AND c.batch <> $batch
            RETURN c.batch AS later
            """;
    private static final String COUNT_SECRET_VALUES =
            """
            MATCH (s:Secret) WHERE elementId(s) IN $nodes AND s.secret_value IS NOT NULL
            RETURN count(s) AS n
            """;
    private static final String REOPEN =
            """
            MATCH (n)-[:SUPERSEDES]->(old)
            WHERE elementId(n) IN $nodes
              AND (old.batch IS NULL OR old.batch <> $batch) AND old.valid_to IS NOT NULL
            WITH DISTINCT old
            REMOVE old.valid_to
            SET old.updated_at = $now
            RETURN count(old) AS n
            """;
    private static final String DELETE_EDGES =
            "MATCH ()-[r]->() WHERE elementId(r) IN $edges DELETE r";
    private static final String DELETE_NODES =
            "MATCH (n) WHERE elementId(n) IN $nodes DETACH DELETE n";
    private static final String TOMBSTONE =
            "MERGE (t:"
                    + SnapshotMerge.REVERTED_BATCH
                    + " {id: $batch})"
                    + " SET t.at = $now, t.updated_at = $now";

    private BatchRevert() {}

    /**
     * @param now {@code Times.format} of the revert, the updated_at of what it reopens
     * @throws WriteRejectedException if no record carries the batch or a later batch built on it
     */
    static RevertResult revert(Transaction tx, String batch, String now) {
        List<String> nodes = elementIds(nodes(tx, batch));
        List<String> edges = elementIds(edges(tx, batch));
        int deleted = nodes.size() + edges.size();
        if (deleted == 0) {
            // The batch is not echoed: it is a request value (invariant 2).
            throw new WriteRejectedException(
                    "no record carries this batch",
                    "Give the batch id a write returned; a reverted batch has no records left.");
        }
        Map<String, Object> params =
                Map.of("batch", batch, "now", now, "nodes", nodes, "edges", edges);
        Set<String> later = later(tx, params);
        if (!later.isEmpty()) {
            throw new WriteRejectedException(
                    "a later batch built on records of this batch",
                    "Revert batch " + String.join(", ", later) + " first, then this one.");
        }
        int secretValues = count(tx, COUNT_SECRET_VALUES, params);
        int reopened = count(tx, REOPEN, params);
        tx.execute(DELETE_EDGES, params).close();
        tx.execute(DELETE_NODES, params).close();
        tx.execute(TOMBSTONE, params).close();
        return new RevertResult(batch, deleted, reopened, secretValues);
    }

    private static Set<Node> nodes(Transaction tx, String batch) {
        Set<Node> found = new HashSet<>();
        for (Label label : tx.getAllLabelsInUse()) {
            try (ResourceIterator<Node> nodes = tx.findNodes(label, GraphWrite.BATCH, batch)) {
                nodes.forEachRemaining(found::add);
            }
        }
        return found;
    }

    private static Set<Relationship> edges(Transaction tx, String batch) {
        Set<Relationship> found = new HashSet<>();
        for (RelationshipType type : tx.getAllRelationshipTypesInUse()) {
            try (ResourceIterator<Relationship> edges =
                    tx.findRelationships(type, GraphWrite.BATCH, batch)) {
                edges.forEachRemaining(found::add);
            }
        }
        return found;
    }

    private static List<String> elementIds(Set<? extends Entity> records) {
        return records.stream().map(Entity::getElementId).toList();
    }

    private static Set<String> later(Transaction tx, Map<String, Object> params) {
        try (Result result = tx.execute(LATER, params)) {
            Set<String> later = new TreeSet<>();
            result.forEachRemaining(row -> later.add((String) row.get("later")));
            return later;
        }
    }

    private static int count(Transaction tx, String cypher, Map<String, Object> params) {
        try (Result result = tx.execute(cypher, params)) {
            return Math.toIntExact((Long) result.next().get("n"));
        }
    }
}
