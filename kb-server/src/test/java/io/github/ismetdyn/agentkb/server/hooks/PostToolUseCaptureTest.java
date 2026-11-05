package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.POST_TOOL_USE;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.context;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.question;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SPEC §8 "Hook yakalama", ARCHITECTURE §11, docs/SECRETS.md, ADR-0022 decision 7: PostToolUse of
 * Claude Code's AskUserQuestion and Codex's request_user_input writes the raw question and answer
 * as a {@code qa-capture} Signal, secret-looking values masked as {@code ****} before writing. One
 * kind + key is one node, so the same question again raises {@code count}. The signal is found by
 * kb_search, marked {@code ham}. Other tools and unreadable payloads: HookEndpointTest. Each test
 * asks its own zz-g9 question (shared graph).
 */
@ExtendWith(OutputCaptureExtension.class)
class PostToolUseCaptureTest extends RunningServerTestBase {

    private static final String BY_QUESTION =
            "MATCH (s:Signal {kind: 'qa-capture'}) WHERE s.payload CONTAINS $question RETURN s.key"
                    + " AS key, s.payload AS payload, s.status AS status, s.count AS count, s.at"
                    + " AS at, s.last_at AS last_at";
    private static final String MASK = "****";
    private static final int NO_CONTENT = 204;
    // No part of a stored value this long may be left behind.
    private static final int SECRET_PART = 8;

    @Autowired private EmbeddedNeo4jStore store;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void an_answered_question_becomes_one_open_qa_capture_signal(String client) throws Exception {
        String asked = "zz-g9-qa-" + client + " which database does billing use?";

        String injected = capture(client, asked, "ornek-db");

        assertThat(injected).as("capture adds nothing to the context").isEmpty();
        Map<String, Object> signal = onlySignalAbout(asked);
        assertThat((String) signal.get("payload")).contains(asked).contains("ornek-db");
        assertThat(signal)
                .containsEntry("status", "open")
                .containsEntry("count", 1L)
                .containsKeys("key", "at", "last_at");
        assertThat(signal.get("last_at")).isEqualTo(signal.get("at"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void the_same_question_again_raises_the_count_of_the_one_signal(String client)
            throws Exception {
        String asked = "zz-g9-again-" + client + " which port does billing listen on?";

        capture(client, asked, "8443");
        capture(client, asked, "8443");

        Map<String, Object> signal = onlySignalAbout(asked);
        assertThat(signal.get("count")).isEqualTo(2L);
        assertThat((String) signal.get("last_at"))
                .isGreaterThanOrEqualTo((String) signal.get("at"));
    }

    /**
     * docs/SECRETS.md: a secret given in a question or an answer is masked before it is written.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
        "claude, zz-g9-mask-1 billing db password?, password=kbtest-Fake-x9y8z7w6v5,"
                + " kbtest-Fake-x9y8z7w6v5",
        "codex, zz-g9-mask-2 deploy token?, ghp_kbtestFAKE0123456789abcdefghijklmnop,"
                + " kbtestFAKE0123456789abcdefghijklmnop",
        "claude, zz-g9-mask-3 is token=kbtest-Fake-q1w2e3r4 still valid?, yes,"
                + " kbtest-Fake-q1w2e3r4"
    })
    void a_secret_looking_value_is_masked_before_it_is_written(
            String client, String asked, String answer, String secret) throws Exception {
        capture(client, asked, answer);

        String payload =
                (String)
                        onlySignalAbout(asked.substring(0, "zz-g9-mask-1".length())).get("payload");
        assertThat(payload).doesNotContain(secret).contains(MASK);
    }

    /**
     * Invariant 2, docs/SECRETS.md: a value stored on a Secret matches no pattern, so it is masked
     * by being known. That has to happen on the raw answer: once the answer is put on one line and
     * cut to its limit, a value with a line break or one cut in two is no longer found whole.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "zz-g9-stored-cut, kbtest-fake-g9-5c81d0a7e2b94f36, 1984",
        "zz-g9-stored-lines, 'kbtest-fake-g9-line-a4e1\nkbtest-fake-g9-line-7d2b', 0"
    })
    void a_stored_secret_value_is_masked_before_the_answer_is_joined_or_cut(
            String entity, String value, int filler) throws Exception {
        McpClient.open(port)
                .text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                entity,
                                "type",
                                "server",
                                "facts",
                                List.of(
                                        Map.of(
                                                "key",
                                                "hostname",
                                                "value",
                                                entity + ".ornek.example",
                                                "env",
                                                "prod")),
                                "source",
                                Map.of("kind", "doc", "ref", "docs/runbook.md")));
        assertThat(new SecretHttp(port).put(entity + "/prod/db", value).statusCode())
                .isEqualTo(NO_CONTENT);
        String asked = entity + " config?";

        capture("claude", asked, "y".repeat(filler) + " " + value);

        Map<String, Object> signal = onlySignalAbout(asked);
        for (String written : List.of((String) signal.get("key"), (String) signal.get("payload"))) {
            for (int i = 0; i + SECRET_PART <= value.length(); i++) {
                assertThat(written).doesNotContain(value.substring(i, i + SECRET_PART));
            }
        }
    }

    @Test
    void a_captured_answer_is_found_by_search_marked_raw() throws Exception {
        capture("claude", "zz-g9-search kbqacaptureprobe hangi vlan?", "vlan 42");

        String found = McpClient.open(port).text("kb_search", Map.of("query", "kbqacaptureprobe"));

        assertThat(found.lines())
                .anyMatch(line -> line.matches("^\\d+\\. .*") && line.contains("ham"));
    }

    /** Invariant 10: question and answer are content and reach no log. */
    @Test
    void question_and_answer_are_never_logged(CapturedOutput output) throws Exception {
        String asked = "zz-g9-log-q-73be which subnet?";
        String answer = "zz-g9-log-a-9f04";

        capture("claude", asked, answer);
        capture("codex", asked, answer);

        assertThat(output.getAll()).doesNotContain("zz-g9-log-q-73be", answer);
    }

    /**
     * One capture handles at most 4 questions (AskUserQuestion's own limit); the rest are ignored.
     */
    @Test
    void one_capture_writes_at_most_four_questions() throws Exception {
        ObjectNode payload = question("claude", "zz-g9-many-q1 first?", "yes");
        ArrayNode questions = (ArrayNode) payload.at("/tool_input/questions");
        ObjectNode answers = (ObjectNode) payload.at("/tool_response/answers");
        for (int i = 2; i <= 6; i++) {
            String asked = "zz-g9-many-q" + i + " next?";
            questions.addObject().put("question", asked);
            answers.put(asked, "yes");
        }

        new HookHttp(port).post(POST_TOOL_USE, "claude", payload);

        for (int i = 1; i <= 4; i++) {
            onlySignalAbout("zz-g9-many-q" + i + " ");
        }
        assertThat(signalsAbout("zz-g9-many-q5 ")).isEmpty();
        assertThat(signalsAbout("zz-g9-many-q6 ")).isEmpty();
    }

    private String capture(String client, String asked, String answer) throws Exception {
        return context(
                POST_TOOL_USE,
                new HookHttp(port).post(POST_TOOL_USE, client, question(client, asked, answer)));
    }

    private Map<String, Object> onlySignalAbout(String asked) {
        List<Map<String, Object>> found = signalsAbout(asked);
        assertThat(found).as("qa-capture signals holding '%s'", asked).hasSize(1);
        return found.getFirst();
    }

    private List<Map<String, Object>> signalsAbout(String asked) {
        return store.read(
                tx ->
                        tx.execute(BY_QUESTION, Map.of("question", asked)).stream()
                                .map(row -> (Map<String, Object>) new HashMap<>(row))
                                .toList());
    }
}
