package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Posts hook payloads to {@code /hooks/{event}?client=} the way integrations/kb-hook.sh does, and
 * reads the answer by the two clients' output contracts. The payloads under
 * src/test/resources/hooks/ are the stdin Claude Code 2.1.318 really sent (claude-*, paths
 * anonymised) and, for AskUserQuestion and Codex, built from the documented and source schemas
 * (codex rust-v0.161.0 codex-rs/hooks/schema/generated).
 */
public final class HookHttp {

    public static final String SESSION_START = "session-start";
    public static final String USER_PROMPT_SUBMIT = "user-prompt-submit";
    public static final String POST_TOOL_USE = "post-tool-use";
    public static final List<String> CLIENTS = List.of("claude", "codex");

    /** kb-hook.sh gives curl --max-time 2; an answer after that never reaches the agent. */
    public static final Duration HOOK_BUDGET = Duration.ofSeconds(2);

    // Claude Code drops additionalContext beyond 10,000 characters (code.claude.com hooks docs).
    private static final int CLAUDE_CONTEXT_LIMIT = 10_000;
    private static final Map<String, String> EVENT_NAMES =
            Map.of(
                    SESSION_START, "SessionStart",
                    USER_PROMPT_SUBMIT, "UserPromptSubmit",
                    POST_TOOL_USE, "PostToolUse");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;

    public HookHttp(int port) {
        this.base = "http://127.0.0.1:" + port + "/hooks/";
    }

    public HttpResponse<String> post(String event, String client, String body)
            throws IOException, InterruptedException {
        return post(event + "?client=" + client, body, Map.of());
    }

    public HttpResponse<String> post(String event, String client, JsonNode payload)
            throws IOException, InterruptedException {
        return post(event, client, JSON.writeValueAsString(payload));
    }

    /** {@code pathAndQuery} after {@code /hooks/}, sent as is, with extra headers. */
    public HttpResponse<String> post(String pathAndQuery, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The context the agent gets: {@code hookSpecificOutput.additionalContext}, or "" for an empty
     * answer. Fails unless the answer is 200 and both clients accept it: Codex rejects any key its
     * output schema lacks (additionalProperties false; no sessionTitle anywhere, no decision on
     * SessionStart), so only {@code hookSpecificOutput} with {@code hookEventName} and {@code
     * additionalContext} is sent.
     */
    public static String context(String event, HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as("%s: %s", event, reply.body()).isEqualTo(200);
        String body = reply.body();
        if (body.isBlank()) {
            return "";
        }
        JsonNode answer = JSON.readTree(body);
        assertThat(answer.isObject()).as(body).isTrue();
        assertThat(Set.copyOf(answer.propertyNames()))
                .as("top-level keys of %s", body)
                .isSubsetOf(Set.of("hookSpecificOutput"));
        if (!answer.has("hookSpecificOutput")) {
            return "";
        }
        JsonNode specific = answer.get("hookSpecificOutput");
        assertThat(Set.copyOf(specific.propertyNames()))
                .as("hookSpecificOutput keys of %s", body)
                .isSubsetOf(Set.of("hookEventName", "additionalContext"));
        assertThat(specific.path("hookEventName").asString(""))
                .as(body)
                .isEqualTo(EVENT_NAMES.get(event));
        String context = specific.path("additionalContext").asString("");
        assertThat(context.length())
                .as("additionalContext length")
                .isLessThan(CLAUDE_CONTEXT_LIMIT);
        return context;
    }

    /** A payload from src/test/resources/hooks/, e.g. {@code claude-SessionStart}. */
    public static ObjectNode fixture(String name) {
        try (InputStream in = HookHttp.class.getResourceAsStream("/hooks/" + name + ".json")) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return (ObjectNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode sessionStart(String client, Path cwd) {
        return fixture(client + "-SessionStart").put("cwd", cwd.toString());
    }

    public static ObjectNode userPromptSubmit(String client, String prompt) {
        return fixture(client + "-UserPromptSubmit").put("prompt", prompt);
    }

    /**
     * The client's question tool answered with {@code answer}: Claude's AskUserQuestion keys the
     * answer by the question text in an object; Codex's request_user_input keys it by question id
     * inside a JSON string, the answer in an array.
     */
    public static ObjectNode question(String client, String question, String answer) {
        if ("claude".equals(client)) {
            ObjectNode payload = fixture("claude-PostToolUse-AskUserQuestion");
            ((ObjectNode) payload.at("/tool_input/questions/0")).put("question", question);
            ObjectNode response = (ObjectNode) payload.get("tool_response");
            ((ObjectNode) response.at("/questions/0")).put("question", question);
            response.putObject("answers").put(question, answer);
            return payload;
        }
        ObjectNode payload = fixture("codex-PostToolUse-request_user_input");
        ((ObjectNode) payload.at("/tool_input/questions/0")).put("question", question);
        ObjectNode answers = JSON.createObjectNode();
        answers.putObject("answers").putObject("framework").putArray("answers").add(answer);
        return payload.put("tool_response", JSON.writeValueAsString(answers));
    }

    /** A working copy whose .git/config names {@code remoteUrl} as origin; no network involved. */
    public static Path gitWorkingCopy(Path dir, String remoteUrl) {
        try {
            Path git = Files.createDirectories(dir.resolve(".git"));
            Files.writeString(
                    git.resolve("config"),
                    """
                    [core]
                    \trepositoryformatversion = 0
                    \tbare = false
                    [remote "origin"]
                    \turl = %s
                    \tfetch = +refs/heads/*:refs/remotes/origin/*
                    [branch "main"]
                    \tremote = origin
                    \tmerge = refs/heads/main
                    """
                            .formatted(remoteUrl));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
