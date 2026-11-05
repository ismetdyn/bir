package io.github.ismetdyn.agentkb.server.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The thin JUnit wrapper that {@code ./mvnw -Peval verify} runs (ADR-0022 decision 8): until CI
 * exists, the gate is the exit code of that build. It fails when eval/questions.yaml scores under
 * 0.85 on the seed, that is fewer than 16 of the 18 questions.
 */
@Tag("eval")
class EvalGateTest extends RunningServerTestBase {

    private static final Path QUESTIONS = Path.of("..", "eval", "questions.yaml");
    // A set that lost questions would pass the gate more easily (ADR-0022 decision 13).
    private static final int SEED_QUESTIONS = 18;

    @Autowired private EvalRunner runner;

    @Test
    void the_seed_scores_at_least_the_gate() {
        EvalReport report = runner.run(QUESTIONS);

        assertThat(report.total()).isEqualTo(SEED_QUESTIONS);
        assertThat(report.meetsGate())
                .as(
                        "eval score %d/%d = %.3f, failed: %s",
                        report.passed(), report.total(), report.score(), report.failed())
                .isTrue();
    }
}
