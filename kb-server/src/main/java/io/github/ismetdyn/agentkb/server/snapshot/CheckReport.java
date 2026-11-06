package io.github.ismetdyn.agentkb.server.snapshot;

import java.util.List;

/** What {@code kb check} found (CheckService); clean only without problems. */
public record CheckReport(List<Problem> problems) {

    public CheckReport {
        problems = List.copyOf(problems);
    }

    public boolean clean() {
        return problems.isEmpty();
    }

    /**
     * One finding. {@code kind} is one of orphan-edge, orphan-fact, unknown-relation,
     * open-conflict, duplicate-id, local-record, git-fsck, unreadable; {@code id} names the record
     * (the repository for git-fsck, the file for unreadable). No part ever holds a value (invariant
     * 2).
     */
    public record Problem(String kind, String id, String detail) {}
}
