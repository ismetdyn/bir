package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static io.github.ismetdyn.agentkb.server.EpisodeProbe.transcript;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.SESSION_START;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/SECRETS.md, invariant 2 (todo G12b LOW): SessionStart stores the client's session_id and
 * transcript_path on the Episode (ADR-0023 decision 1), but never one that looks like a secret. A
 * secret-looking session id opens no Episode; a secret-looking path is left out. The session still
 * gets its brief. The values are fake and new per run.
 */
class EpisodeSecretTest extends RunningServerTestBase {

    private static final String HOLDING =
            "MATCH (n) UNWIND keys(n) AS k WITH n[k] AS v"
                    + " WHERE v IS :: STRING AND v CONTAINS $s RETURN count(*) AS c";
    private static final int AWS_KEY_BODY = 16;

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path cwd;

    private EpisodeProbe probe;

    @BeforeEach
    void probe() {
        probe = new EpisodeProbe(port, store);
    }

    /** A fake AWS access key id fits the plain-id shape SessionStart accepts. */
    @Test
    void a_secret_looking_session_id_is_not_stored_and_the_brief_still_comes() throws Exception {
        String secretId =
                "AKIA"
                        + UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, AWS_KEY_BODY)
                                .toUpperCase(Locale.ROOT);

        HttpResponse<String> reply =
                new HookHttp(port)
                        .post(
                                SESSION_START,
                                "claude",
                                HookHttp.sessionStart("claude", cwd)
                                        .put("session_id", secretId)
                                        .put("transcript_path", transcript("claude", "plain")));

        String brief = HookHttp.context(SESSION_START, reply);
        assertThat(brief).isNotBlank().doesNotContain(secretId).doesNotContain("Episode:");
        assertThat(probe.episodes("claude", secretId)).isEmpty();
        assertThat(valuesHolding(secretId)).as("graph values holding it").isZero();
    }

    @Test
    void a_secret_looking_transcript_path_opens_the_episode_without_it() throws Exception {
        String sessionId = newSessionId();
        String secretPath =
                "/home/user/.claude/projects/x/token=kbtestFAKE"
                        + sessionId.replace("-", "")
                        + ".jsonl";

        HttpResponse<String> reply =
                new HookHttp(port)
                        .post(
                                SESSION_START,
                                "claude",
                                HookHttp.sessionStart("claude", cwd)
                                        .put("session_id", sessionId)
                                        .put("transcript_path", secretPath));

        assertThat(HookHttp.context(SESSION_START, reply)).contains("Episode:");
        assertThat(probe.episodes("claude", sessionId))
                .singleElement()
                .satisfies(episode -> assertThat(episode).doesNotContainKey("transcript_path"));
        assertThat(valuesHolding(secretPath)).as("graph values holding it").isZero();
    }

    private long valuesHolding(String value) {
        return (Long) probe.rows(HOLDING, Map.of("s", value)).getFirst().get("c");
    }
}
