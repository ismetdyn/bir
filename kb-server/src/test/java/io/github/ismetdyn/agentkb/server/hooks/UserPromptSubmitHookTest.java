package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.USER_PROMPT_SUBMIT;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.context;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.fixture;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.userPromptSubmit;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SPEC S3 and §11.2, ARCHITECTURE §11: UserPromptSubmit keeps the prompt in the PromptRing (RAM,
 * never logged) and injects at most 3 search results above the score threshold; recall hides
 * nothing (invariant 12), and the budget is applied after ordering. The ring is what SPEC §7 rule 7
 * checks user_stated evidence against (ADR-0022 decision 29). The PromptRing is shared by the
 * server tests, so every prompt here carries its own zz-g9-* words.
 */
@ExtendWith(OutputCaptureExtension.class)
class UserPromptSubmitHookTest extends RunningServerTestBase {

    private static final int MAX_HITS = 3;
    private static final Pattern RESULT_LINE = Pattern.compile("^\\d+\\. .*");
    private static final String FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) RETURN f.source AS source";

    @Autowired private PromptRing promptRing;
    @Autowired private EmbeddedNeo4jStore store;

    private HookHttp hooks;

    @BeforeEach
    void client() {
        hooks = new HookHttp(port);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void s3_the_record_asked_about_is_already_in_context(String client) throws Exception {
        String recalled = recall(client, "ornek-app-01 hangi LB arkasında?");

        assertThat(recalled).contains("ornek-app-01");
        assertThat(recalled.lines().filter(line -> RESULT_LINE.matcher(line).matches()))
                .hasSizeBetween(1, MAX_HITS);
    }

    /** The payloads as the clients send them; context() checks the answer both accept. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void a_real_payload_gets_an_answer_the_client_accepts(String client) throws Exception {
        String recalled =
                context(
                        USER_PROMPT_SUBMIT,
                        hooks.post(
                                USER_PROMPT_SUBMIT, client, fixture(client + "-UserPromptSubmit")));

        assertThat(recalled.lines().filter(line -> RESULT_LINE.matcher(line).matches()))
                .hasSizeLessThanOrEqualTo(MAX_HITS);
    }

    /**
     * A Turkish suffix after an apostrophe is not part of the word: ornek-app-01'in is
     * ornek-app-01.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ornek-app-01'in IP'si nedir?", "ornek-app-01’e hangi LB bakıyor?"})
    void a_suffix_after_an_apostrophe_still_recalls_the_record(String prompt) throws Exception {
        assertThat(recall("claude", prompt)).contains("ornek-app-01");
    }

    @Test
    void a_prompt_with_no_match_at_all_adds_no_context() throws Exception {
        assertThat(recall("claude", "zz-g9-nothing-qx7 zz-g9-nothing-wv3")).isEmpty();
    }

    /** Invariant 12: an old value is recalled, marked, with the record it belonged to. */
    @Test
    void an_old_ip_is_recalled_marked_as_old() throws Exception {
        String recalled = recall("claude", "192.0.2.20 hangi sunucuydu?");

        assertThat(recalled.lines())
                .anyMatch(line -> line.contains("ornek-app-01") && line.contains("eski ("));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void the_prompt_is_kept_in_the_prompt_ring(String client) throws Exception {
        String prompt = "zz-g9-ring-" + client + " sunucusunun adı zz-g9-ring.ornek.example";
        Instant before = Instant.now();

        recall(client, prompt);

        assertThat(promptRing.contains(prompt, before.plusSeconds(1))).isTrue();
    }

    /**
     * Decision 29 through the hook: what the user typed makes user_stated evidence; evidence the
     * user never typed leaves the source agent.
     */
    @ParameterizedTest(name = "evidence {1} -> {2}")
    @CsvSource({
        "zz-g9-ev1, 'zz-g9-ev1 adı zz-g9-ev1.ornek.example', user_stated",
        "zz-g9-ev2, 'zz-g9-ev2 adı zz-g9-ev2.ornek.example olsun dedi', agent"
    })
    void evidence_from_a_prompt_seen_by_the_hook_makes_user_stated(
            String entity, String evidence, String expected) throws Exception {
        recall("claude", "Bilgi: " + entity + " adı " + entity + ".ornek.example.");

        String reply =
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
                                        Map.of("kind", "user_stated", "evidence", evidence)));

        assertThat(reply).doesNotStartWith("{\"error\"");
        List<Map<String, Object>> facts =
                store.read(tx -> tx.execute(FACTS, Map.of("id", entity)).stream().toList());
        assertThat(facts)
                .singleElement()
                .satisfies(fact -> assertThat(fact.get("source")).isEqualTo(expected));
    }

    /** Invariant 10: the prompt is content; it reaches no log, at any level that is on. */
    @Test
    void the_prompt_is_never_logged(CapturedOutput output) throws Exception {
        String sentinel = "zz-g9-logged-prompt-41c7";

        recall("claude", "ornek-app-01 " + sentinel);
        recall("codex", sentinel);

        assertThat(output.getAll()).doesNotContain(sentinel);
    }

    private String recall(String client, String prompt) throws IOException, InterruptedException {
        return context(
                USER_PROMPT_SUBMIT,
                hooks.post(USER_PROMPT_SUBMIT, client, userPromptSubmit(client, prompt)));
    }
}
