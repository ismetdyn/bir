package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Node;

/**
 * SPEC §7 rule 3 and ADR-0022 decision 7: a value waiting as a conflict opens a {@code conflict}
 * Signal ABOUT its entity. One kind + key is one node, so the same dispute again only raises count
 * and last_at. The key is entity, fact key and env: every disputed value of one slot is one
 * dispute. A signal is local (invariant 8), never deleted (invariant 5) and carries no batch.
 */
final class ConflictSignals {

    private static final String CONFLICT = "conflict";

    private final GraphWrite graph;

    ConflictSignals(GraphWrite graph) {
        this.graph = graph;
    }

    /**
     * @param current the value the waiting one disputes; null when an existing waiting value is
     *     seen again, whose signal then already exists or, for imported data, gets one now
     */
    void raise(Node entity, Fact waiting, @Nullable Fact current) {
        raise(
                entity,
                waiting.key(),
                waiting.env(),
                payload(waiting, current),
                Times.format(waiting.observedAt()));
    }

    /**
     * Lead decision (A): one scan found several values for one single-valued slot and wrote the
     * first (ScanSlots). The payload names files and lines only, no value (invariant 2).
     */
    void raise(Node entity, ScanSlots.Dispute dispute, Instant at) {
        ScanSlots.Slot slot = dispute.slot();
        raise(
                entity,
                slot.key(),
                slot.env(),
                slot.key()
                        + " @"
                        + slot.env()
                        + ": one scan found several values; kept the one at "
                        + dispute.keptAt()
                        + ", other values at "
                        + String.join(", ", dispute.others()),
                Times.format(at));
    }

    private void raise(Node entity, String key, String env, String payload, String at) {
        String entityId = (String) entity.getProperty(GraphWrite.ID);
        Node created =
                SignalWrite.raise(graph, CONFLICT, entityId + "/" + key + "/" + env, payload, at);
        if (created != null) {
            graph.localEdge(created, "ABOUT", entity);
        }
    }

    // Values reach here only after WriteCheck refused secret-looking ones (SPEC §6.1 payload).
    private static String payload(Fact waiting, @Nullable Fact current) {
        String disputed =
                waiting.key()
                        + " @"
                        + waiting.env()
                        + ": "
                        + waiting.value()
                        + " ("
                        + Ids.normalize(waiting.source().name())
                        + ")";
        return current == null
                ? disputed + " waits as a conflict"
                : disputed
                        + " disputes "
                        + current.value()
                        + " ("
                        + Ids.normalize(current.source().name())
                        + ")";
    }
}
