package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotMerge;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The writes that keep the graph as a whole rather than add knowledge to it: schema, snapshot
 * import, {@code kb revert}, {@code kb restore} and {@code kb sync}'s merge. Only {@link
 * WriteService} creates it and hands it out through {@link WriteService#maintenance()}, so the
 * single writer stays the one entry (invariant 1); it writes through the same store, so under the
 * same writer lock.
 */
public final class GraphMaintenance {

    private final EmbeddedNeo4jStore store;
    private final RepoSchema schema;

    GraphMaintenance(EmbeddedNeo4jStore store, RepoSchema schema) {
        this.store = store;
        this.schema = schema;
    }

    /**
     * Creates the fixed schema and brings the fulltext index to its current definition; called on
     * every start, whatever the graph holds (GraphSchema).
     */
    public void ensureSchema() {
        GraphSchema.ensure(store, Set.of(), Set.of());
    }

    /**
     * Loads snapshot rows as they are: no schema, key or length checks and {@code updated_at} kept
     * (invariant 11), all rows in one transaction; only an {@code id} or secret_value prop is
     * ignored (RecordImport, invariant 2). Fails, leaving the data unchanged, only when a row
     * cannot exist in a graph: a duplicate id or an edge whose endpoint is missing.
     */
    public void importRecords(Snapshot snapshot) {
        GraphSchema.ensureFor(store, snapshot);
        store.write(tx -> RecordImport.create(tx, snapshot));
    }

    /**
     * {@code kb revert --batch <id>} (BatchRevert): deletes what the batch opened and reopens what
     * it closed, in one transaction.
     *
     * @throws WriteRejectedException if the batch is empty or unknown, or a later batch built on
     *     it; nothing changed
     */
    public RevertResult revert(@Nullable String batch) {
        if (batch == null || batch.isBlank()) {
            throw new WriteRejectedException(
                    "no batch given",
                    "Give the batch id a write returned, e.g. kb revert --batch <id>.");
        }
        String now = Times.format(Instant.now());
        return store.write(tx -> BatchRevert.revert(tx, batch, now));
    }

    /**
     * Whether {@link #revert} would go through now (BatchRevert#check), in a read transaction;
     * nothing is written.
     */
    public RevertCheck revertCheck(String batch) {
        return store.read(tx -> BatchRevert.check(tx, batch));
    }

    /**
     * {@code kb restore} (RecordRestore): rebuilds the graph from {@code snapshot} in one
     * transaction, keeping local data (ADR-0022 decision 38). Rows are taken as they are (invariant
     * 11). Fails, leaving the graph unchanged, only when a row cannot exist in a graph.
     */
    public RestoreResult replaceAll(Snapshot snapshot) {
        GraphSchema.ensureFor(store, snapshot);
        return store.write(tx -> RecordRestore.replace(tx, snapshot));
    }

    /**
     * {@code kb sync}'s graph side (SyncService): merges {@code mine}, the graph as exported, with
     * the remote's rows (SnapshotMerge), loads the result through {@link #replaceAll} when it
     * differs and settles it (SyncSettle). The caller holds the writer lock, so {@code mine} is
     * still the graph.
     *
     * @return the conflicts the merge found
     */
    int mergeSnapshot(Snapshot mine, Snapshot theirs) {
        SnapshotMerge.Merged merged = SnapshotMerge.merge(mine, theirs);
        if (!merged.snapshot().equals(mine)) {
            // ponytail: rebuilds the whole graph (the restore path, 10k records in seconds); load
            // only the changed rows if a sync of a large graph gets too slow.
            replaceAll(merged.snapshot());
        }
        return store.write(
                tx ->
                        SyncSettle.settle(
                                new GraphWrite(tx, Ulid.next(), Instant.now()),
                                merged.ties(),
                                schema.load()));
    }
}
