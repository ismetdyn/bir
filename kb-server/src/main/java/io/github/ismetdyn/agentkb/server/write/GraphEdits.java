package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The user's edits of existing records from REST (CLIENTS §1, ADR-0025 decisions 9-12 and 15-18):
 * close a fact or relation, edit or (un)archive an entity, resolve a conflict, accept or reject a
 * proposal. Each is one transaction under the writer lock and one batch of its own with no Episode
 * (decision 9), so {@code kb revert --batch} undoes it. Only {@link WriteService} creates it and
 * hands it out through {@link WriteService#edits()}: the single writer stays the one entry
 * (invariant 1).
 *
 * @see WriteRejectedException for every refused edit; nothing was written
 */
public final class GraphEdits {

    /** {@code POST /conflicts/{id}/resolve} (decision 11). */
    public enum Resolution {
        /** The waiting value becomes the user's current value (ADR-0022 decision 23). */
        ACCEPT_NEW,
        /** Only the waiting value closes. */
        KEEP_OLD
    }

    private final EmbeddedNeo4jStore store;
    private final RepoSchema schema;

    GraphEdits(EmbeddedNeo4jStore store, RepoSchema schema) {
        this.store = store;
        this.schema = schema;
    }

    /** Closes an open fact (valid_to and closed_batch; decision 10). */
    public EditResult closeFact(String factId) {
        return edit(graph -> FactEdits.closeFact(graph, factId));
    }

    /** Closes an open relation between two entities, found by its edge id. */
    public EditResult closeRelation(String relationId) {
        return edit(graph -> FactEdits.closeRelation(graph, relationId));
    }

    /**
     * Sets aliases and summary of an entity; a null one stays as it is, an empty alias list clears
     * them.
     */
    public EditResult editEntity(
            String entityId, @Nullable List<String> aliases, @Nullable String summary) {
        return edit(graph -> EntityEdits.edit(graph, entityId, aliases, summary));
    }

    public EditResult archive(String entityId) {
        return edit(
                graph -> EntityEdits.setArchived(graph, EntityEdits.entity(graph, entityId), true));
    }

    public EditResult unarchive(String entityId) {
        return edit(
                graph ->
                        EntityEdits.setArchived(graph, EntityEdits.entity(graph, entityId), false));
    }

    /** Resolves a value waiting as a conflict (decision 11); the conflict signal stays open. */
    public EditResult resolveConflict(String conflictFactId, Resolution resolution) {
        return edit(graph -> FactEdits.resolve(graph, schema, conflictFactId, resolution));
    }

    /** Accepts an open proposal and applies it (decision 12). */
    public EditResult acceptProposal(String id) {
        return edit(graph -> ProposalReview.accept(graph, schema, id));
    }

    /**
     * Rejects an open proposal; {@code note}, if given, is kept as its review_note (decision 18).
     */
    public EditResult rejectProposal(String id, @Nullable String note) {
        return edit(graph -> ProposalReview.reject(graph, id, note));
    }

    // The batch is made under the writer lock, so batch ids sort in commit order (BatchRevert).
    private EditResult edit(Consumer<GraphWrite> change) {
        return new EditResult(
                store.write(
                        tx -> {
                            String batch = Ulid.next();
                            change.accept(new GraphWrite(tx, batch, Instant.now()));
                            return batch;
                        }));
    }
}
