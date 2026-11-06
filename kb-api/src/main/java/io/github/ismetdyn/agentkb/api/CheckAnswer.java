package io.github.ismetdyn.agentkb.api;

import java.util.List;

/** {@code GET /api/v1/check}: clean only without problems. No part ever holds a value. */
public record CheckAnswer(boolean clean, List<Problem> problems) {

    public CheckAnswer {
        problems = List.copyOf(problems);
    }

    /** One finding: its {@code kind} (orphan-edge, git-fsck, …), the record it names, a detail. */
    public record Problem(String kind, String id, String detail) {}
}
