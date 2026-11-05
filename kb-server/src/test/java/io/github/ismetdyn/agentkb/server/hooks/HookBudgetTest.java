package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.HOOK_BUDGET;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.SESSION_START;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.USER_PROMPT_SUBMIT;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.context;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.gitWorkingCopy;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.sessionStart;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.userPromptSubmit;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §12 "Hook" and ARCHITECTURE §11: KB hooks finish within 2 s (kb-hook.sh gives curl
 * --max-time 2) and fail open. The server cuts work that would overrun and answers 200 with no
 * context rather than let the client's own timeout drop the answer. The 300 ms brief/recall target
 * of SPEC §12 is a performance goal, not checked here (timing on a shared test JVM).
 */
class HookBudgetTest extends RunningServerTestBase {

    @TempDir Path workingCopy;

    @Test
    void the_brief_answers_within_the_budget() throws Exception {
        Path repo = gitWorkingCopy(workingCopy, "git@git.ornek.example:ornek-grup/ornek-proje.git");
        JsonNode payload = sessionStart("codex", repo);
        post(SESSION_START, payload);

        Timed timed = timed(SESSION_START, payload);

        assertThat(timed.took()).isLessThan(HOOK_BUDGET);
        assertThat(timed.context()).contains("ornek-proje");
    }

    @Test
    void the_recall_answers_within_the_budget() throws Exception {
        JsonNode payload = userPromptSubmit("codex", "ornek-db hangi porttan dinliyor?");
        post(USER_PROMPT_SUBMIT, payload);

        assertThat(timed(USER_PROMPT_SUBMIT, payload).took()).isLessThan(HOOK_BUDGET);
    }

    /**
     * A pasted log far beyond a search query (and full of query syntax) is still answered in time,
     * without the search engine's failure.
     */
    @Test
    void a_very_long_prompt_is_answered_within_the_budget() throws Exception {
        String prompt = "ornek-app-01 192.0.2.21 hata ( \" * AND ".repeat(12_000);
        post(USER_PROMPT_SUBMIT, userPromptSubmit("claude", "zz-g9-warmup"));

        Timed timed = timed(USER_PROMPT_SUBMIT, userPromptSubmit("claude", prompt));

        assertThat(timed.took()).isLessThan(HOOK_BUDGET);
        assertThat(timed.context()).doesNotContain("Exception", "org.apache.lucene");
    }

    /**
     * A read that never ends (a .git/config that is a FIFO with no writer, so opening it blocks) is
     * cut inside the server; the session still gets a 200 answer in time, without the project
     * brief.
     */
    @Test
    void a_read_that_hangs_is_cut_and_answered_empty_within_the_budget() throws Exception {
        Path config = Files.createDirectories(workingCopy.resolve(".git")).resolve("config");
        Process mkfifo = new ProcessBuilder("mkfifo", config.toString()).start();
        assertThat(mkfifo.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(mkfifo.exitValue()).as("mkfifo").isZero();
        try {
            Timed timed = timed(SESSION_START, sessionStart("claude", workingCopy));

            assertThat(timed.took()).isLessThan(HOOK_BUDGET);
            assertThat(timed.context()).doesNotContain("ornek-proje");
        } finally {
            release(config);
        }
    }

    private record Timed(Duration took, String context) {}

    private Timed timed(String event, JsonNode payload) throws IOException, InterruptedException {
        long started = System.nanoTime();
        HttpResponse<String> reply = post(event, payload);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        return new Timed(took, context(event, reply));
    }

    private HttpResponse<String> post(String event, JsonNode payload)
            throws IOException, InterruptedException {
        return new HookHttp(port).post(event, clientOf(payload), payload);
    }

    /** Codex payloads carry {@code model}; Claude Code's SessionStart and prompt do not. */
    private static String clientOf(JsonNode payload) {
        return payload.has("model") ? "codex" : "claude";
    }

    /**
     * Opening the FIFO read-write never blocks on Linux and gives a server thread still blocked on
     * the read end a writer; closing it then hands that thread end of file.
     */
    private static void release(Path fifo) throws IOException {
        try (RandomAccessFile both = new RandomAccessFile(fifo.toFile(), "rw")) {
            assertThat(both.getFD().valid()).isTrue();
        }
    }
}
