package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import java.util.List;

/** What {@code kb check} found (CheckService); clean only without problems. */
public record CheckReport(List<Problem> problems) {

    public CheckReport {
        problems = List.copyOf(problems);
    }

    public boolean clean() {
        return problems.isEmpty();
    }

    /** ADR-0026: ids and details as the answer shows them, a stored value masked. */
    CheckReport masked(SecretMask mask) {
        return new CheckReport(
                problems.stream()
                        .map(p -> new Problem(p.kind(), mask.text(p.id()), mask.text(p.detail())))
                        .toList());
    }

    /**
     * One finding. {@code kind} is one of orphan-edge, orphan-fact, unknown-relation,
     * open-conflict, duplicate-id, local-record, missing-blob, blob-hash-mismatch, git-fsck,
     * unreadable; {@code id} names the record (the repository for git-fsck, the file or document
     * for unreadable). No part ever holds a value (invariant 2).
     */
    public record Problem(String kind, String id, String detail) {}
}
