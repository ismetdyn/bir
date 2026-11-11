package io.github.ismetdyn.agentkb.server.hooks;

import io.github.ismetdyn.agentkb.server.scan.SessionScan;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import io.github.ismetdyn.agentkb.server.write.EpisodeWrite;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * {@code POST /hooks/<event>?client=<claude|codex>}, what kb hook carries (ARCHITECTURE §11):
 * session-start opens the session's Episode and gives the brief with its id, starting a background
 * scan of the working copy when SPEC S1 calls for one, user-prompt-submit records the prompt with
 * its session and gives the recall, post-tool-use captures an answered question and gives nothing.
 * The answer is empty or only {@code hookSpecificOutput {hookEventName, additionalContext}}: Codex
 * refuses any other key (additionalProperties false), and Claude Code takes the same shape. Both
 * clients' payloads are read the same way, so the client is only checked. An unknown event or
 * client is an empty 404; a payload that is no JSON object an empty 200, so a session is never
 * blocked. Behind OriginGuard like every path (ADR-0020).
 */
@RestController
class HookController {

    private static final Map<String, String> EVENTS =
            Map.of(
                    "session-start", "SessionStart",
                    "user-prompt-submit", "UserPromptSubmit",
                    "post-tool-use", "PostToolUse");
    private static final Logger LOG = LoggerFactory.getLogger(HookController.class);
    private static final Set<String> CLIENTS = Set.of("claude", "codex");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // docs/MCP-TOOLS.md kb_brief, integrations/AGENT-RULES.md: the one line with the episode id.
    private static final String EPISODE_LINE =
            "Episode: %s (bu oturum; kb_remember'da source.episode, kb_propose ve kb_scan'de"
                    + " episode olarak ver)\n";

    private final BriefService briefs;
    private final RecallService recall;
    private final CaptureService capture;
    private final WriteService writes;
    // SessionScan::afterSessionStart (cwd, Episode or null); returns at once (SPEC S1).
    private final BiConsumer<String, @Nullable String> scanAfterSessionStart;

    @Autowired
    HookController(
            BriefService briefs,
            RecallService recall,
            CaptureService capture,
            WriteService writes,
            SessionScan scans) {
        this.briefs = briefs;
        this.recall = recall;
        this.capture = capture;
        this.writes = writes;
        this.scanAfterSessionStart = scans::afterSessionStart;
    }

    /** Without background scans: the hook alone, as SessionStartFailOpenTest builds it. */
    HookController(
            BriefService briefs,
            RecallService recall,
            CaptureService capture,
            WriteService writes) {
        this.briefs = briefs;
        this.recall = recall;
        this.capture = capture;
        this.writes = writes;
        this.scanAfterSessionStart = (cwd, episode) -> {};
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
                    case "SessionStart" -> sessionStart(client, payload);
                    case "UserPromptSubmit" ->
                            recall.recall(text(payload.path("prompt")), session(client, payload));
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

    /**
     * The brief, then the line naming the session's Episode, which SessionStart opens or refreshes
     * (ADR-0023 decision 3); without a usable session_id the brief alone. The scan of SPEC S1, if
     * one is due, starts apart after the brief, so the brief does not wait for it; it is stamped
     * with the session's Episode, so that is opened first.
     */
    private String sessionStart(String client, JsonNode payload) {
        String cwd = text(payload.path("cwd"));
        String brief = briefs.forCwd(cwd).orElse("");
        Optional<String> session = openSession(client, payload);
        scanAfterSessionStart.accept(cwd, session.orElse(null));
        return session.map(
                        episode ->
                                (brief.isEmpty() ? "" : brief + "\n")
                                        + EPISODE_LINE.formatted(episode))
                .orElse(brief);
    }

    /**
     * Fail-open (ARCHITECTURE §11): a failed Episode write costs the Episode line, never the brief.
     * Only the exception class is logged; its message may carry the session id or a path (invariant
     * 10).
     */
    private Optional<String> openSession(String client, JsonNode payload) {
        try {
            return writes.openSession(
                    client,
                    text(payload.path("session_id")),
                    text(payload.path("transcript_path")));
        } catch (RuntimeException e) {
            LOG.warn("event=episode_open_failed error={}", e.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * The session the prompt came in; null unless its id is one SessionStart could open an Episode
     * for, so the PromptRing keeps no unbounded id (checklist A1) and loses no match.
     */
    private static PromptRing.@Nullable Session session(String client, JsonNode payload) {
        return EpisodeWrite.sessionId(text(payload.path("session_id")))
                .map(id -> new PromptRing.Session(client, id))
                .orElse(null);
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
