package io.github.ismetdyn.agentkb.server.write;

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
}
