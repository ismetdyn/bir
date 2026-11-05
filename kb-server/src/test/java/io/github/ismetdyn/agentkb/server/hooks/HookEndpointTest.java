package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.POST_TOOL_USE;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.context;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.fixture;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.question;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

/**
 * The surface of {@code /hooks/{event}?client=}: three events (ADR-0022 decision 14 removed stop)
 * and two clients; anything else is refused without detail. Only the two question tools are
 * captured, and a payload that cannot be read writes nothing and never blocks the session
 * (kb-hook.sh drops any non-2xx answer). The endpoint stays behind OriginGuard (ADR-0020), so a web
 * page cannot plant a qa-capture signal with a cross-site POST.
 */
class HookEndpointTest extends RunningServerTestBase {

    // A new node or a raised count both change it.
    private static final String QA_CAPTURES =
            "MATCH (s:Signal {kind: 'qa-capture'}) RETURN count(s) + coalesce(sum(s.count), 0)"
                    + " AS n";

    @Autowired private EmbeddedNeo4jStore store;

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "stop?client=claude",
                "session-end?client=claude",
                "SessionStart?client=claude",
                "post-tool-use-failure?client=codex",
                "session-start?client=cursor",
                "user-prompt-submit?client=",
                "post-tool-use?client=CLAUDE"
            })
    void an_unknown_event_or_client_is_refused_without_detail(String pathAndQuery)
            throws Exception {
        HttpResponse<String> reply = new HookHttp(port).post(pathAndQuery, "{}", Map.of());

        assertThat(reply.statusCode()).as(reply.body()).isIn(400, 404);
        ErrorBodyAssertions.assertNoLeak(reply.body());
    }

    /** The Codex matcher is a regex, so a longer tool name such as the _async one may arrive. */
    @Test
    void any_other_tool_writes_nothing() throws Exception {
        HookHttp hooks = new HookHttp(port);
        long before = qaCaptures();
        ObjectNode async = question("codex", "zz-g9-async which framework?", "React");
        async.put("tool_name", "request_user_input_async");

        HttpResponse<String> bash =
                hooks.post(POST_TOOL_USE, "claude", fixture("claude-PostToolUse-Bash"));
        HttpResponse<String> other = hooks.post(POST_TOOL_USE, "codex", async);

        assertThat(context(POST_TOOL_USE, bash)).isEmpty();
        assertThat(context(POST_TOOL_USE, other)).isEmpty();
        assertThat(qaCaptures()).isEqualTo(before);
    }

    /**
     * A refused question (the tool result is an error string), a Codex result that is not JSON, a
     * payload without the question, a body that is not JSON and one that is no object.
     */
    @Test
    void an_unreadable_payload_writes_nothing_and_leaks_nothing() throws Exception {
        HookHttp hooks = new HookHttp(port);
        long before = qaCaptures();
        ObjectNode refused = question("claude", "zz-g9-bad-1 which framework?", "React");
        refused.put("tool_response", "Error: the user declined to answer");
        ObjectNode notJson = question("codex", "zz-g9-bad-2 which framework?", "React");
        notJson.put("tool_response", "{\"answers\":");
        ObjectNode noQuestion = question("claude", "zz-g9-bad-3 which framework?", "React");
        noQuestion.remove("tool_input");
        noQuestion.remove("tool_response");

        List<HttpResponse<String>> replies =
                List.of(
                        hooks.post(POST_TOOL_USE, "claude", refused),
                        hooks.post(POST_TOOL_USE, "codex", notJson),
                        hooks.post(POST_TOOL_USE, "claude", noQuestion),
                        hooks.post(POST_TOOL_USE, "claude", "{not json"),
                        hooks.post(POST_TOOL_USE, "codex", "[]"));

        for (HttpResponse<String> reply : replies) {
            assertThat(reply.statusCode()).as(reply.body()).isIn(200, 400);
            ErrorBodyAssertions.assertNoLeak(reply.body());
        }
        assertThat(qaCaptures()).isEqualTo(before);
    }

    @Test
    void a_cross_site_post_is_refused_and_writes_nothing() throws Exception {
        long before = qaCaptures();

        HttpResponse<String> reply =
                new HookHttp(port)
                        .post(
                                POST_TOOL_USE + "?client=claude",
                                question("claude", "zz-g9-origin which framework?", "React")
                                        .toString(),
                                Map.of("Origin", "http://attacker.example"));

        assertThat(reply.statusCode()).isEqualTo(403);
        assertThat(qaCaptures()).isEqualTo(before);
    }

    private long qaCaptures() {
        return store.read(tx -> ((Number) tx.execute(QA_CAPTURES).next().get("n")).longValue());
    }
}
