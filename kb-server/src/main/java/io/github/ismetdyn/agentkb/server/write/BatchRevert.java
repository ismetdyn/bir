package io.github.ismetdyn.agentkb.server.write;

import java.util.List;
import java.util.Map;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * {@code kb revert --batch <id>} (ARCHITECTURE §5, ADR-0022 decisions 10 and 22-23): the one
 * deletion invariant 5 allows. A fact never changes in place, so a batch is undone by deleting
 * every node and edge it opened (they carry its batch) and reopening what it closed (what its nodes
 * point to with SUPERSEDES). Signals carry no batch and stay; an ABOUT edge of a signal to a
 * deleted record goes with it. A Secret the batch opened goes with the value it holds now, also one
 * the user set later (the node keeps the batch that opened it); the result counts those values.
 * Runs in the caller's one transaction.
 *
 * <p>No partial revert: when a later batch built on a record of this one (an edge of the later
 * batch touches it: a HAS_FACT onto its entity, a SUPERSEDES that closed its fact), the whole
 * revert is refused and the hint names the later batch, which goes first.
 *
 * <p>ponytail: the batch is found by a scan of every node and edge (no index on batch); add one if
 * a revert of a large graph gets slow. A fact a rescan closed without SUPERSEDES (ScanGraph) is not
 * found and stays closed.
 */
final class BatchRevert {

    private static final String LATER =
            """
            MATCH (n {batch: $batch})-[r]-()
            WHERE r.batch IS NOT NULL AND r.batch <> $batch
            RETURN DISTINCT r.batch AS later ORDER BY later
            """;
    private static final String COUNT_NODES = "MATCH (n {batch: $batch}) RETURN count(n) AS n";
    private static final String COUNT_EDGES =
            "MATCH ()-[r {batch: $batch}]->() RETURN count(r) AS n";
    private static final String COUNT_SECRET_VALUES =
            "MATCH (s:Secret {batch: $batch}) WHERE s.secret_value IS NOT NULL RETURN count(s) AS n";
    private static final String REOPEN =
            """
            MATCH (n {batch: $batch})-[:SUPERSEDES]->(old)
            WHERE (old.batch IS NULL OR old.batch <> $batch) AND old.valid_to IS NOT NULL
            WITH DISTINCT old
            REMOVE old.valid_to
            SET old.updated_at = $now
            RETURN count(old) AS n
            """;
    private static final String DELETE_EDGES = "MATCH ()-[r {batch: $batch}]->() DELETE r";
    private static final String DELETE_NODES = "MATCH (n {batch: $batch}) DETACH DELETE n";

    private BatchRevert() {}

    /**
     * @param now {@code Times.format} of the revert, the updated_at of what it reopens
     * @throws WriteRejectedException if no record carries the batch or a later batch built on it
     */
    static RevertResult revert(Transaction tx, String batch, String now) {
        Map<String, Object> params = Map.of("batch", batch, "now", now);
        int deleted = count(tx, COUNT_NODES, params) + count(tx, COUNT_EDGES, params);
        if (deleted == 0) {
            // The batch is not echoed: it is a request value (invariant 2).
            throw new WriteRejectedException(
                    "no record carries this batch",
                    "Give the batch id a write returned; a reverted batch has no records left.");
        }
        List<String> later = later(tx, params);
        if (!later.isEmpty()) {
            throw new WriteRejectedException(
                    "a later batch built on records of this batch",
                    "Revert batch " + String.join(", ", later) + " first, then this one.");
        }
        int secretValues = count(tx, COUNT_SECRET_VALUES, params);
        int reopened = count(tx, REOPEN, params);
        tx.execute(DELETE_EDGES, params).close();
        tx.execute(DELETE_NODES, params).close();
        return new RevertResult(batch, deleted, reopened, secretValues);
    }

    private static List<String> later(Transaction tx, Map<String, Object> params) {
        try (Result result = tx.execute(LATER, params)) {
            return result.stream().map(row -> (String) row.get("later")).toList();
        }
    }

    private static int count(Transaction tx, String cypher, Map<String, Object> params) {
        try (Result result = tx.execute(cypher, params)) {
            return Math.toIntExact((Long) result.next().get("n"));
        }
    }
}
