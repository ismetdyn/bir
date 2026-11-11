package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.snapshot.SnapshotMerge;
import java.util.HashMap;
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
 * point to with SUPERSEDES, or what carries it as closed_batch: a user edit's close, ADR-0025
 * decision 10). A user edit's Revision puts its old values back before it goes (decision 15); a
 * batch that accepted a schema proposal is refused (decision 17). Signals carry no batch and stay;
 * an ABOUT edge of a signal to a deleted record goes with it. A Secret the batch opened goes with
 * its value; one the user set a value on since carries the batch of that PUT and stays
 * (SecretWrite.put), only this batch's HAS_SECRET edge to it goes. The result counts the values
 * deleted. Runs in the caller's one transaction.
 *
 * <p>The revert leaves a {@code RevertedBatch {id: <batch>, at}} node (ADR-0022 decision 42). It
 * goes into the snapshot, so {@code kb sync} does not bring the batch back from another machine
 * (SnapshotMerge) and drops it there too.
 *
 * <p>No partial revert: when a later batch built on a record of this one (an edge of the later
 * batch touches it: a HAS_FACT onto its entity, a SUPERSEDES that closed its fact; a conflict value
 * of the later batch waits next to an open fact of this batch; a later Revision revises a record
 * this batch revised; a later user edit closed a record of this batch (closed_batch), whose revert
 * would find nothing to reopen once this batch deleted it; or a later batch opened a fact or
 * relation in the slot of one this batch closed, so reopening it would leave two), the whole revert
 * is refused and the hint names the later batch, which goes first. Batch ids are ULIDs, so "later"
 * is their text order.
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
            UNION
            MATCH (rv:Revision)-[:REVISES]->()<-[:REVISES]-(other:Revision)
            WHERE elementId(rv) IN $nodes AND other.batch > $batch
            RETURN other.batch AS later
            UNION
            MATCH (e)-[:HAS_FACT]->(f:Fact) WHERE elementId(f) IN $closedNodes
            MATCH (e)-[:HAS_FACT]->(g:Fact)
            WHERE g.key = f.key AND g.env = f.env AND g.valid_to IS NULL AND g.batch > $batch
            RETURN g.batch AS later
            UNION
            MATCH (a)-[r]->(b) WHERE elementId(r) IN $closedEdges
            MATCH (a)-[s]->(b)
            WHERE type(s) = type(r) AND coalesce(s.env, '') = coalesce(r.env, '')
              AND s.valid_to IS NULL AND s.batch > $batch
            RETURN s.batch AS later
            UNION
            MATCH (n) WHERE elementId(n) IN $nodes AND n.closed_batch <> $batch
            RETURN n.closed_batch AS later
            UNION
            MATCH ()-[r]->() WHERE elementId(r) IN $edges AND r.closed_batch <> $batch
            RETURN r.closed_batch AS later
            """;
    // No index on closed_batch: a revert is rare and the graph personal (10k records in ms).
    private static final String CLOSED_NODES =
            "MATCH (n) WHERE n.closed_batch = $batch RETURN elementId(n) AS id";
    private static final String CLOSED_EDGES =
            "MATCH ()-[r]->() WHERE r.closed_batch = $batch RETURN elementId(r) AS id";
    private static final String SCHEMA_ACCEPTS =
            """
            MATCH (rv:Revision)-[:REVISES]->(:Proposal {kind: 'schema', status: 'accepted'})
            WHERE elementId(rv) IN $nodes
            RETURN count(rv) AS n
            """;
    private static final String COUNT_SECRET_VALUES =
            """
            MATCH (s:Secret) WHERE elementId(s) IN $nodes AND s.secret_value IS NOT NULL
            RETURN count(s) AS n
            """;
    private static final String REOPEN_CLOSED_NODES =
            """
            MATCH (n) WHERE elementId(n) IN $closedNodes
            REMOVE n.valid_to, n.closed_batch
            SET n.updated_at = $now
            RETURN count(n) AS n
            """;
    private static final String REOPEN_CLOSED_EDGES =
            """
            MATCH ()-[r]->() WHERE elementId(r) IN $closedEdges
            REMOVE r.valid_to, r.closed_batch
            SET r.updated_at = $now
            RETURN count(r) AS n
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
        Records records = Records.of(tx, batch);
        if (records.isEmpty()) {
            // The batch is not echoed: it is a request value (invariant 2).
            throw new WriteRejectedException(
                    "no record carries this batch",
                    "Give the batch id a write returned; a reverted batch has no records left.");
        }
        if (count(tx, SCHEMA_ACCEPTS, records.params()) > 0) {
            throw new WriteRejectedException(
                    "this batch accepted a schema proposal",
                    "A schema change is normative and not undone by kb revert (ADR-0025 decision"
                            + " 17); change schema/facts.yaml through a new schema proposal.");
        }
        Map<String, Object> params = records.params();
        params.put("now", now);
        Set<String> later = later(tx, params);
        if (!later.isEmpty()) {
            throw new WriteRejectedException(
                    "a later batch built on records of this batch",
                    "Revert batch " + String.join(", ", later) + " first, then this one.");
        }
        int secretValues = count(tx, COUNT_SECRET_VALUES, params);
        Revisions.restore(tx, records.nodes(), now);
        // The closes first: REOPEN then skips what they already reopened.
        int reopened =
                count(tx, REOPEN_CLOSED_NODES, params)
                        + count(tx, REOPEN_CLOSED_EDGES, params)
                        + count(tx, REOPEN, params);
        tx.execute(DELETE_EDGES, params).close();
        tx.execute(DELETE_NODES, params).close();
        tx.execute(TOMBSTONE, params).close();
        return new RevertResult(
                batch, records.nodes().size() + records.edges().size(), reopened, secretValues);
    }

    /**
     * Whether {@link #revert} would go through now, read only: no record or a schema accept is no,
     * without a later batch; otherwise the later batches that built on this one, by id.
     */
    static RevertCheck check(Transaction tx, String batch) {
        Records records = Records.of(tx, batch);
        if (records.isEmpty() || count(tx, SCHEMA_ACCEPTS, records.params()) > 0) {
            return new RevertCheck(false, List.of());
        }
        List<String> later = List.copyOf(later(tx, records.params()));
        return new RevertCheck(later.isEmpty(), later);
    }

    /** The element ids of what the batch opened and closed. */
    private record Records(
            String batch,
            List<String> nodes,
            List<String> edges,
            List<String> closedNodes,
            List<String> closedEdges) {

        static Records of(Transaction tx, String batch) {
            return new Records(
                    batch,
                    elementIds(BatchRevert.nodes(tx, batch)),
                    elementIds(BatchRevert.edges(tx, batch)),
                    elementIds(tx, CLOSED_NODES, batch),
                    elementIds(tx, CLOSED_EDGES, batch));
        }

        boolean isEmpty() {
            return nodes.isEmpty()
                    && edges.isEmpty()
                    && closedNodes.isEmpty()
                    && closedEdges.isEmpty();
        }

        /** The query parameters; a mutable map, so revert adds {@code now}. */
        Map<String, Object> params() {
            return new HashMap<>(
                    Map.of(
                            "batch", batch,
                            "nodes", nodes,
                            "edges", edges,
                            "closedNodes", closedNodes,
                            "closedEdges", closedEdges));
        }
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

    private static List<String> elementIds(Transaction tx, String cypher, String batch) {
        try (Result result = tx.execute(cypher, Map.of("batch", batch))) {
            return result.stream().map(row -> (String) row.get("id")).toList();
        }
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
