package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import java.util.List;

/**
 * ARCHITECTURE §5 step 5. Fact ids; {@code conflicts} are the facts of this write that wait with
 * status conflict next to a more trusted current value (SPEC §7 rule 3), and the ids of relations
 * the user closed that this write wrote again without opening them (ADR-0025 decision 16).
 */
public record WriteResult(
        String entity,
        boolean created,
        String batch,
        List<String> added,
        List<String> refreshed,
        List<String> closed,
        List<String> conflicts) {

    public WriteResult {
        added = List.copyOf(added);
        refreshed = List.copyOf(refreshed);
        closed = List.copyOf(closed);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * ADR-0026: the entity a write found by alias or address may hold a stored value in its id. The
     * other ids are ULIDs the server made.
     */
    WriteResult masked(SecretMask mask) {
        return new WriteResult(
                mask.text(entity), created, batch, added, refreshed, closed, conflicts);
    }
}
