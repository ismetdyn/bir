package io.github.ismetdyn.agentkb.server.hooks;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /hooks/<event>?client=<claude|codex>}, what integrations/kb-hook.sh carries
 * (ARCHITECTURE §11): session-start gives the brief, user-prompt-submit the recall, post-tool-use
 * captures an answered question and gives nothing. The answer is empty or only {@code
 * hookSpecificOutput {hookEventName, additionalContext}}: Codex refuses any other key
 * (additionalProperties false), and Claude Code takes the same shape. Both clients' payloads are
 * read the same way, so the client is only checked. An unknown event or client is an empty 404; a
 * payload that is no JSON object an empty 200, so a session is never blocked. Behind OriginGuard
 * like every path (ADR-0020).
 */
@RestController
class HookController {

    private static final Map<String, String> EVENTS =
            Map.of(
                    "session-start", "SessionStart",
                    "user-prompt-submit", "UserPromptSubmit",
                    "post-tool-use", "PostToolUse");
    private static final Set<String> CLIENTS = Set.of("claude", "codex");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BriefService briefs;
    private final RecallService recall;
    private final CaptureService capture;

    HookController(BriefService briefs, RecallService recall, CaptureService capture) {
        this.briefs = briefs;
        this.recall = recall;
        this.capture = capture;
    }

    @PostMapping("/hooks/{event}")
    ResponseEntity<byte[]> hook(
            @PathVariable String event,
            @RequestParam(required = false) @Nullable String client,
            @RequestBody(required = false) byte @Nullable [] body) {
        String eventName = EVENTS.get(event);
        if (eventName == null || client == null || !CLIENTS.contains(client)) {
            return ResponseEntity.notFound().build();
        }
        JsonNode payload = payload(body);
        if (!payload.isObject()) {
            return ResponseEntity.ok().build();
        }
        String context =
                switch (eventName) {
                    case "SessionStart" -> briefs.forCwd(text(payload.path("cwd"))).orElse("");
                    case "UserPromptSubmit" -> recall.recall(text(payload.path("prompt")));
                    default -> {
                        capture.capture(payload);
                        yield "";
                    }
                };
        if (context.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.putObject("hookSpecificOutput")
                .put("hookEventName", eventName)
                .put("additionalContext", context);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsBytes(answer));
    }

    /** The body as JSON; what cannot be read is no payload, never an error for the session. */
    private static JsonNode payload(byte @Nullable [] body) {
        if (body == null || body.length == 0) {
            return MissingNode.getInstance();
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }
}
