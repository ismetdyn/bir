package io.github.ismetdyn.agentkb.server.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * EvalRunner (ADR-0022 decision 8) scores eval/questions.yaml against the seed through the read
 * tools: a question passes if one expected record is in the first three results (kb_read: the
 * record returned) and every {@code expect_text} is in the answer. The gate is a score of at least
 * 0.85. eval/questions.yaml is a referee file, so broken copies are written to a temporary
 * directory.
 */
class EvalRunnerTest extends RunningServerTestBase {

    private static final Path QUESTIONS = Path.of("..", "eval", "questions.yaml");
    private static final int SEED_QUESTIONS = 18;
    private static final String NO_SUCH_RECORD = "no-such-record";

    @Autowired private EvalRunner runner;

    @TempDir Path dir;

    @Test
    void every_question_of_the_set_is_scored() {
        EvalReport report = runner.run(QUESTIONS);

        assertThat(report.total()).isEqualTo(SEED_QUESTIONS);
        assertThat(report.passed() + report.failed().size()).isEqualTo(SEED_QUESTIONS);
        assertThat(report.score()).isEqualTo((double) report.passed() / SEED_QUESTIONS);
    }

    @Test
    void a_broken_expectation_fails_its_question_and_lowers_the_score_by_one() throws IOException {
        EvalReport original = runner.run(QUESTIONS);
        assertThat(original.failed())
                .as("precondition: q09 passes as written")
                .doesNotContain("q09");

        EvalReport broken =
                runner.run(
                        brokenCopy(
                                "query: \"192.0.2.21\"\n    expect: [ornek-app-01]",
                                "query: \"192.0.2.21\"\n    expect: [" + NO_SUCH_RECORD + "]"));

        assertThat(broken.failed()).contains("q09");
        assertThat(broken.passed()).isEqualTo(original.passed() - 1);
        assertThat(broken.score()).isLessThan(original.score());
    }

    @Test
    void a_missing_expect_text_fails_the_question() throws IOException {
        EvalReport original = runner.run(QUESTIONS);
        assertThat(original.failed())
                .as("precondition: q13 passes as written")
                .doesNotContain("q13");

        EvalReport broken =
                runner.run(
                        brokenCopy(
                                "expect_text: [\"192.0.2.20\"]",
                                "expect_text: [\"192.0.2.20\", \"no-such-text\"]"));

        assertThat(broken.failed()).contains("q13");
        assertThat(broken.passed()).isEqualTo(original.passed() - 1);
    }

    /** Three questions broken leave at most 15 of 18 (0.83): the gate must fail. */
    @Test
    void three_broken_expectations_fail_the_gate() throws IOException {
        EvalReport original = runner.run(QUESTIONS);
        assertThat(original.failed())
                .as("precondition: q02, q09 and q13 pass as written")
                .doesNotContain("q02", "q09", "q13");
        String text = Files.readString(QUESTIONS);
        for (String[] change :
                new String[][] {
                    {
                        "query: \"ornek-f5\"\n    expect: [ornek-lb]",
                        "query: \"ornek-f5\"\n    expect: [x]"
                    },
                    {
                        "query: \"192.0.2.21\"\n    expect: [ornek-app-01]",
                        "query: \"192.0.2.21\"\n    expect: [x]"
                    },
                    {"expect_text: [\"192.0.2.20\"]", "expect_text: [\"no-such-text\"]"}
                }) {
            assertThat(text).as("the referee file still has this text").contains(change[0]);
            text = text.replace(change[0], change[1]);
        }

        EvalReport broken = runner.run(write("broken.yaml", text));

        assertThat(broken.failed()).contains("q02", "q09", "q13");
        assertThat(broken.passed()).isLessThanOrEqualTo(15);
        assertThat(broken.meetsGate()).isFalse();
    }

    /** 17 of 20 is exactly 0.85 and meets the gate; 16 of 20 does not. */
    @Test
    void the_gate_is_a_score_of_at_least_0_85() throws IOException {
        EvalReport atGate = runner.run(write("at-gate.yaml", questions(17, 3)));
        EvalReport belowGate = runner.run(write("below-gate.yaml", questions(16, 4)));

        assertThat(atGate.passed()).isEqualTo(17);
        assertThat(atGate.score()).isEqualTo(0.85);
        assertThat(atGate.meetsGate()).isTrue();
        assertThat(belowGate.passed()).isEqualTo(16);
        assertThat(belowGate.meetsGate()).isFalse();
    }

    /** A question the runner cannot run is a failure; skipping it would raise the score. */
    @Test
    void a_question_with_an_unknown_tool_counts_as_failed() throws IOException {
        String text =
                questions(1, 0)
                        + """
                          - id: unknown-tool
                            question: "?"
                            tool: kb_no_such_tool
                            query: "ornek-f5"
                            expect: [ornek-lb]
                        """;

        EvalReport report = runner.run(write("unknown-tool.yaml", text));

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.failed()).containsExactly("unknown-tool");
    }

    private Path brokenCopy(String before, String after) throws IOException {
        String text = Files.readString(QUESTIONS);
        assertThat(text).as("the referee file still has this text").contains(before);
        return write("broken.yaml", text.replace(before, after));
    }

    private Path write(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text);
    }

    /** {@code good} questions that pass on the seed (eval q02's form) and {@code bad} that fail. */
    private static String questions(int good, int bad) {
        String goodOnes =
                IntStream.rangeClosed(1, good)
                        .mapToObj(i -> question("good-" + i, "ornek-lb"))
                        .collect(Collectors.joining());
        String badOnes =
                IntStream.rangeClosed(1, bad)
                        .mapToObj(i -> question("bad-" + i, NO_SUCH_RECORD))
                        .collect(Collectors.joining());
        return "version: 4\nquestions:\n" + goodOnes + badOnes;
    }

    private static String question(String id, String expect) {
        return """
                  - id: %s
                    question: "ornek-f5 nedir?"
                    tool: kb_read
                    query: "ornek-f5"
                    expect: [%s]
                """
                .formatted(id, expect);
    }
}
