package io.github.ismetdyn.agentkb.server.eval;

import java.util.List;

/**
 * The score of one eval run (ADR-0022 decision 8): {@code failed} holds the ids of the questions
 * that did not pass, in file order.
 */
public record EvalReport(int total, int passed, List<String> failed) {

    /** eval/questions.yaml "Hedef: >= %85". */
    public static final double GATE = 0.85;

    public EvalReport {
        failed = List.copyOf(failed);
    }

    public double score() {
        return total == 0 ? 0 : (double) passed / total;
    }

    public boolean meetsGate() {
        return score() >= GATE;
    }
}
