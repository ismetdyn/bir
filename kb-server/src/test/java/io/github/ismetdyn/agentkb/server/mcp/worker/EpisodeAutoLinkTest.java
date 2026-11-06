package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.NONE;
import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0023 decision 3, ARCHITECTURE §5: how a write finds its Episode without being told.
 *
 * <ul>
 *   <li>A {@code user_stated} write whose evidence matches a prompt UserPromptSubmit recorded for a
 *       session is linked to that session's Episode, before {@code source.episode}.
 *   <li>Decision 6, measured 2026-11-06: Codex 0.161.0 sends its session id in {@code tools/call
 *       params._meta} ({@code sessionId}, {@code threadId}, {@code x-codex-turn-metadata}); Claude
 *       Code 2.1.318 sends none (only {@code claudecode/toolUseId}), and neither sends one in an
 *       HTTP header. So a Codex write links without the argument and a Claude Code write does not.
 * </ul>
 */
class EpisodeAutoLinkTest extends RunningServerTestBase {

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path cwd;

    private EpisodeProbe probe;
    private McpClient mcp;

    @BeforeEach
    void open() throws Exception {
        probe = new EpisodeProbe(port, store);
        mcp = McpClient.open(port);
    }

    @Test
    void a_heard_user_stated_write_links_to_the_prompts_episode_over_the_argument()
            throws Exception {
        Session heard = probe.start("claude", newSessionId(), cwd);
        Session other = probe.start("codex", newSessionId(), cwd);
        String said = "zz-g12b-heard sunucusunun adı zz-g12b-heard.ornek.example";
        probe.prompt(heard, said);

        String reply =
                remember(
                        "zz-g12b-heard",
                        "zz-g12b-heard.ornek.example",
                        Map.of("kind", "user_stated", "evidence", said),
                        other.episode(),
                        Map.of());

        assertThat(sources("zz-g12b-heard")).containsOnly("user_stated");
        assertThat(probe.stamps(EpisodeProbe.batch(reply)))
                .isNotEmpty()
                .containsOnly(heard.episode());
    }

    @Test
    void unheard_user_stated_evidence_keeps_the_argument_episode() throws Exception {
        Session prompted = probe.start("claude", newSessionId(), cwd);
        Session argument = probe.start("codex", newSessionId(), cwd);
        probe.prompt(prompted, "zz-g12b-unheard için başka bir şey söyledim");

        String reply =
                remember(
                        "zz-g12b-unheard",
                        "zz-g12b-unheard.ornek.example",
                        Map.of(
                                "kind",
                                "user_stated",
                                "evidence",
                                "zz-g12b-unheard adı zz-g12b-unheard.ornek.example"),
                        argument.episode(),
                        Map.of());

        assertThat(sources("zz-g12b-unheard")).containsOnly("agent");
        assertThat(probe.stamps(EpisodeProbe.batch(reply)))
                .isNotEmpty()
                .containsOnly(argument.episode());
    }

    @Test
    void a_codex_write_links_to_its_session_through_meta_without_the_argument() throws Exception {
        String sessionId = newSessionId();
        Session codex = probe.start("codex", sessionId, cwd);
        // The tools/call _meta Codex 0.161.0 sent in the measurement, ids replaced.
        Map<String, Object> meta =
                Map.of(
                        "sessionId",
                        sessionId,
                        "threadId",
                        sessionId,
                        "windowId",
                        sessionId + ":0",
                        "callId",
                        "call_kbtest_1",
                        "x-codex-turn-metadata",
                        Map.of(
                                "session_id", sessionId,
                                "thread_id", sessionId,
                                "turn_id", "kbtest-turn-1"));

        String reply =
                remember(
                        "zz-g12b-codex-meta",
                        "zz-g12b-codex-meta.ornek.example",
                        Map.of("kind", "code"),
                        "",
                        meta);

        assertThat(probe.stamps(EpisodeProbe.batch(reply)))
                .isNotEmpty()
                .containsOnly(codex.episode());
    }

    @Test
    void a_claude_code_write_without_the_argument_has_no_episode() throws Exception {
        probe.start("claude", newSessionId(), cwd);
        Map<String, Object> meta =
                Map.of("claudecode/toolUseId", "toolu_01kbtest", "progressToken", 7);

        String reply =
                remember(
                        "zz-g12b-claude-meta",
                        "zz-g12b-claude-meta.ornek.example",
                        Map.of("kind", "code"),
                        "",
                        meta);

        assertThat(probe.stamps(EpisodeProbe.batch(reply))).isNotEmpty().containsOnly(NONE);
    }

    /** A new server with one hostname; an empty {@code episode} is left out. */
    private String remember(
            String entity,
            String hostname,
            Map<String, String> source,
            String episode,
            Map<String, ?> meta)
            throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("entity", entity);
        arguments.put("type", "server");
        arguments.put("facts", List.of(Map.of("key", "hostname", "value", hostname)));
        Map<String, String> withEpisode = new HashMap<>(source);
        if (!episode.isEmpty()) {
            withEpisode.put("episode", episode);
        }
        arguments.put("source", withEpisode);
        String reply = mcp.text("kb_remember", arguments, meta);
        assertThat(reply).doesNotStartWith("{\"error\"");
        return reply;
    }

    private List<Object> sources(String entity) {
        return probe
                .rows(
                        "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) RETURN f.source AS s",
                        Map.of("id", entity))
                .stream()
                .map(row -> row.get("s"))
                .toList();
    }
}
