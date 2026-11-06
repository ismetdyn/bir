package io.github.ismetdyn.agentkb.server.hooks;

import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.write.StoredSecretMask;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * PostToolUse of a question tool (SPEC §8 "Hook yakalama", ARCHITECTURE §11): each answered
 * question becomes a raw {@code qa-capture} Signal, with what looks like a secret masked as {@code
 * ****} before it is written (docs/SECRETS.md). Claude Code's AskUserQuestion answers in an object
 * keyed by the question text; Codex's request_user_input answers in a JSON string keyed by the
 * question id, each answer a list. Any other tool, a refused question (its result is an error
 * string) or an unreadable payload writes nothing. Nothing of the question or answer is logged.
 */
@Component
public class CaptureService {

    static final String KIND = "qa-capture";
    // Exact names: the Codex matcher is a regex and also lets request_user_input_async through.
    private static final Set<String> QUESTION_TOOLS =
            Set.of("AskUserQuestion", "request_user_input");
    // AskUserQuestion's own limit; a payload with more is not trusted to write more signals.
    private static final int MAX_QUESTIONS = 4;
    // Question and answer share the SPEC §6.1 body limit of 4,000 characters.
    private static final int TEXT_LIMIT = 2_000;
    // What is masked at most: any longer text is cut far beyond what is kept, and the patterns
    // stay cheap on a pasted log.
    private static final int MASK_WINDOW = 4 * TEXT_LIMIT;
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WriteService writes;
    private final StoredSecretMask stored;

    public CaptureService(WriteService writes, StoredSecretMask stored) {
        this.writes = writes;
        this.stored = stored;
    }

    /** Writes a signal per answered question of {@code payload}; anything else writes nothing. */
    public void capture(JsonNode payload) {
        if (!QUESTION_TOOLS.contains(text(payload.path("tool_name")))) {
            return;
        }
        JsonNode answers = answers(payload.path("tool_response"));
        Instant at = Instant.now();
        List<JsonNode> questions =
                payload.path("tool_input").path("questions").values().stream()
                        .limit(MAX_QUESTIONS)
                        .toList();
        for (JsonNode question : questions) {
            String asked = text(question.path("question"));
            String answer = answer(answers, asked, text(question.path("id")));
            if (!asked.isBlank() && !answer.isBlank()) {
                // Stored values on the raw text: after written() joins lines and cuts, a value
                // with a line break or one cut in two is no longer found whole.
                String q = written(stored.mask(asked));
                String a = written(stored.mask(answer));
                writes.raiseSignal(KIND, q + "\n" + a, "Q: " + q + " · A: " + a, at);
            }
        }
    }

    /**
     * {@code text} cut where its first secret-looking part starts, {@code ****} standing for the
     * rest. Over-masks the words after a secret rather than leave a key body behind its header.
     */
    static String mask(String text) {
        String kept = text;
        boolean masked = false;
        while (SecretPatterns.looksLikeSecret(kept)) {
            kept = kept.substring(0, lastSecretStart(kept));
            masked = true;
        }
        return masked ? kept + WriteService.MASK : text;
    }

    /**
     * The last word start from which the rest of {@code text} still looks like a secret. A match in
     * a suffix is a match in every longer suffix, so a binary search finds it. {@code text} itself
     * must look like one.
     */
    private static int lastSecretStart(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 1; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i - 1))
                    && !Character.isWhitespace(text.charAt(i))) {
                starts.add(i);
            }
        }
        int low = 0;
        int high = starts.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (SecretPatterns.looksLikeSecret(text.substring(starts.get(mid)))) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return starts.get(low);
    }

    /** Claude Code: an object; Codex: that object as a JSON string. */
    private static JsonNode answers(JsonNode response) {
        JsonNode result = response;
        if (response.isString()) {
            try {
                result = JSON.readTree(response.asString());
            } catch (JacksonException e) {
                return MissingNode.getInstance();
            }
        }
        return result.isObject() ? result.path("answers") : MissingNode.getInstance();
    }

    /** By question text (Claude Code), else by id (Codex); a list of labels is joined. */
    private static String answer(JsonNode answers, String asked, String id) {
        JsonNode answer = answers.path(asked);
        if (answer.isMissingNode() && !id.isEmpty()) {
            answer = answers.path(id);
        }
        if (answer.isString()) {
            return answer.asString();
        }
        StringJoiner labels = new StringJoiner(", ");
        answer.path("answers").values().forEach(label -> labels.add(text(label)));
        return labels.toString();
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }

    /**
     * On one line, as a search result shows it. Masked before it is cut to the limit, so a secret
     * cut in two cannot slip past the patterns; masking only shortens, so the part of a window that
     * survives the cut is masked.
     */
    private static String written(String text) {
        String line = SPACES.matcher(capped(text, MASK_WINDOW)).replaceAll(" ").strip();
        return capped(mask(line), TEXT_LIMIT);
    }

    /** At most {@code limit} characters of {@code text}, a surrogate pair never split. */
    static String capped(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit;
        // Do not split a surrogate pair.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
