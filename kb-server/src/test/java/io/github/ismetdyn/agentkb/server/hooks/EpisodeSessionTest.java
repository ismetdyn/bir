package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.ULID;
import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static io.github.ismetdyn.agentkb.server.EpisodeProbe.transcript;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.POST_TOOL_USE;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.USER_PROMPT_SUBMIT;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.userPromptSubmit;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.api.MaintenanceHttp;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * ADR-0023 decision 3, ARCHITECTURE §11: SessionStart opens the Episode of {@code (client,
 * session_id)} or refreshes its {@code last_at}, and the brief carries its id in one line. An
 * Episode holds no prompt or answer text (decision 1, ADR-0017), has no batch, and {@code kb revert
 * --batch} never deletes it (decision 2, invariant 5). Every session id here is a new UUID.
 */
@ExtendWith(OutputCaptureExtension.class)
class EpisodeSessionTest extends RunningServerTestBase {

    // ADR-0023 decision 1, plus updated_at, which every record carries for sync (SPEC §10).
    private static final Set<String> EPISODE_KEYS =
            Set.of(
                    "id",
                    "kind",
                    "client",
                    "session_id",
                    "transcript_path",
                    "started_at",
                    "last_at",
                    "updated_at");
    private static final long OVER_ONE_SECOND_MS = 1_100;

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private PromptRing promptRing;

    @TempDir Path cwd;

    private EpisodeProbe probe;

    @BeforeEach
    void probe() {
        probe = new EpisodeProbe(port, store);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude", "codex"})
    void session_start_opens_the_sessions_episode_and_the_brief_names_it(String client)
            throws Exception {
        String sessionId = newSessionId();

        Session session = probe.start(client, sessionId, cwd);

        assertThat(session.episode()).matches(ULID);
        assertThat(session.brief())
                .as("the brief carries the episode id")
                .contains(session.episode());
        assertThat(probe.episodes(client, sessionId).getFirst())
                .containsEntry("kind", "session")
                .containsEntry("client", client)
                .containsEntry("session_id", sessionId)
                .containsEntry("transcript_path", transcript(client, sessionId))
                .containsKeys("started_at", "last_at");
    }

    @Test
    void a_second_session_start_refreshes_last_at_and_opens_no_second_episode() throws Exception {
        String sessionId = newSessionId();
        Session first = probe.start("claude", sessionId, cwd);
        Map<String, Object> before = probe.episodes("claude", sessionId).getFirst();
        // Times has second precision; a refresh within the same second would not show.
        Thread.sleep(OVER_ONE_SECOND_MS);

        Session again = probe.start("claude", sessionId, cwd);

        assertThat(again.episode()).isEqualTo(first.episode());
        assertThat(again.brief()).contains(first.episode());
        Map<String, Object> after = probe.episodes("claude", sessionId).getFirst();
        assertThat(after.get("started_at")).isEqualTo(before.get("started_at"));
        assertThat((String) after.get("last_at")).isGreaterThan((String) before.get("last_at"));
    }

    @Test
    void the_same_session_id_from_the_other_client_is_another_episode() throws Exception {
        String sessionId = newSessionId();

        Session claude = probe.start("claude", sessionId, cwd);
        Session codex = probe.start("codex", sessionId, cwd);

        assertThat(codex.episode()).isNotEqualTo(claude.episode());
        assertThat(codex.brief()).contains(codex.episode()).doesNotContain(claude.episode());
    }

    /** ADR-0023 decision 3: a plain session id is kept with the prompt it came with. */
    @Test
    void a_plain_session_id_is_kept_with_the_prompt() throws Exception {
        String prompt = "zz-g12b-plain-session sunucusu";
        String sessionId = newSessionId();

        new HookHttp(port)
                .post(
                        USER_PROMPT_SUBMIT,
                        "claude",
                        userPromptSubmit("claude", prompt).put("session_id", sessionId));

        assertThat(promptRing.session(prompt, Instant.now()))
                .contains(new PromptRing.Session("claude", sessionId));
    }

    /**
     * Checklist A1: the ring keeps only a session id SessionStart could have opened an Episode for,
     * so its memory stays bounded; the prompt itself is still kept, without a session.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {129, 1_000_000})
    void an_over_long_session_id_is_not_kept(int length) throws Exception {
        String prompt = "zz-g12b-long-session-" + length + " sunucusu";
        Instant before = Instant.now();

        new HookHttp(port)
                .post(
                        USER_PROMPT_SUBMIT,
                        "claude",
                        userPromptSubmit("claude", prompt).put("session_id", "a".repeat(length)));

        assertThat(promptRing.contains(prompt, before.plusSeconds(1))).isTrue();
        assertThat(promptRing.session(prompt, before.plusSeconds(1))).isEmpty();
    }

    @Test
    void a_session_id_that_is_no_plain_id_is_not_kept() throws Exception {
        String prompt = "zz-g12b-odd-session sunucusu";
        Instant before = Instant.now();

        new HookHttp(port)
                .post(
                        USER_PROMPT_SUBMIT,
                        "claude",
                        userPromptSubmit("claude", prompt).put("session_id", "../x y"));

        assertThat(promptRing.contains(prompt, before.plusSeconds(1))).isTrue();
        assertThat(promptRing.session(prompt, before.plusSeconds(1))).isEmpty();
    }

    /** ADR-0023 decision 1 and invariant 10: no prompt or answer text in the Episode or a log. */
    @Test
    void an_episode_holds_no_prompt_or_answer_text_and_none_reaches_the_log(CapturedOutput output)
            throws Exception {
        String promptMarker = "kbtest-prompt-marker-3c9e";
        String answerMarker = "kbtest-answer-marker-81fd";
        Session session = probe.start("claude", newSessionId(), cwd);
        String said = "zz-g12b-quiet sunucusu " + promptMarker + " notunu taşır";
        probe.prompt(session, said);
        HttpResponse<String> captured =
                new HookHttp(port)
                        .post(
                                POST_TOOL_USE,
                                "claude",
                                HookHttp.question(
                                                "claude",
                                                "zz-g12b-quiet hangi ortamda?",
                                                answerMarker)
                                        .put("session_id", session.sessionId()));
        HookHttp.context(POST_TOOL_USE, captured);
        McpClient.open(port)
                .text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                "zz-g12b-quiet",
                                "type",
                                "server",
                                "facts",
                                List.of(Map.of("key", "note", "value", said)),
                                "source",
                                Map.of(
                                        "kind",
                                        "user_stated",
                                        "evidence",
                                        said,
                                        "episode",
                                        session.episode())));

        Map<String, Object> episode = probe.episode(session.episode()).orElseThrow();
        assertThat(episode.keySet()).as("Episode properties").isSubsetOf(EPISODE_KEYS);
        assertThat(episode.values().toString()).doesNotContain(promptMarker, answerMarker);
        assertThat(output.getAll())
                .as("application log")
                .doesNotContain(promptMarker, answerMarker);
    }

    /** ADR-0023 decision 2: an Episode has no batch, so reverting its writes leaves it. */
    @Test
    void reverting_a_write_of_the_session_never_deletes_its_episode() throws Exception {
        Session session = probe.start("codex", newSessionId(), cwd);
        String reply =
                McpClient.open(port)
                        .text(
                                "kb_remember",
                                Map.of(
                                        "entity",
                                        "zz-g12b-revert",
                                        "type",
                                        "server",
                                        "facts",
                                        List.of(Map.of("key", "os", "value", "rhel 9")),
                                        "source",
                                        Map.of("kind", "code", "episode", session.episode())));
        String batch = EpisodeProbe.batch(reply);
        assertThat(probe.stamps(batch)).isNotEmpty().containsOnly(session.episode());

        HttpResponse<String> reverted =
                new MaintenanceHttp(port)
                        .post(MaintenanceHttp.REVERT, "{\"batch\": \"" + batch + "\"}");

        assertThat(reverted.statusCode()).as(reverted.body()).isEqualTo(200);
        assertThat(probe.stamps(batch)).as("the batch's records are gone").isEmpty();
        assertThat(probe.episode(session.episode())).as("the Episode stays").isPresent();
        assertThat(probe.episode(session.episode()).orElseThrow()).doesNotContainKey("batch");
    }
}
