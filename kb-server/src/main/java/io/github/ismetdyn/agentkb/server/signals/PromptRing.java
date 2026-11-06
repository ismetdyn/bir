package io.github.ismetdyn.agentkb.server.signals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * SPEC §7 rule 7: the user's messages of the last two hours, in RAM only (never written to the
 * graph, a log or a file). Evidence for {@code user_stated} must be found in one of them word for
 * word; anything else is stored as {@code agent}.
 */
@Component
public class PromptRing {

    private static final Duration WINDOW = Duration.ofHours(2);
    // Retained memory is bounded by MAX_PROMPTS x MAX_PROMPT_CHARS, plus a session id that
    // HookController checked against the Episode id shape; old prompts leave on record.
    private static final int MAX_PROMPTS = 500;
    private static final int MAX_PROMPT_CHARS = 8 * 1024;

    /** The client session a prompt was sent in, as UserPromptSubmit reported it (ADR-0023). */
    public record Session(String client, String id) {}

    private record Prompt(String text, Instant at, @Nullable Session session) {}

    private final Deque<Prompt> prompts = new ArrayDeque<>();

    public void record(String prompt, Instant at) {
        record(prompt, at, null);
    }

    public synchronized void record(String prompt, Instant at, @Nullable Session session) {
        Instant since = at.minus(WINDOW);
        prompts.removeIf(old -> old.at().isBefore(since));
        prompts.addLast(new Prompt(head(prompt), at, session));
        while (prompts.size() > MAX_PROMPTS) {
            prompts.removeFirst();
        }
    }

    /**
     * The first {@link #MAX_PROMPT_CHARS} of {@code prompt}, ending before the word the limit cuts:
     * a cut word would hold as a whole token ("bastionhost" cut to "ba").
     */
    private static String head(String prompt) {
        if (prompt.length() <= MAX_PROMPT_CHARS) {
            return prompt;
        }
        int end = MAX_PROMPT_CHARS;
        while (end > 0 && !Character.isWhitespace(prompt.charAt(end))) {
            end--;
        }
        return prompt.substring(0, end);
    }

    /** Blank evidence is part of every prompt, so it never counts. */
    public synchronized boolean contains(String evidence, Instant now) {
        String words = evidence.strip();
        if (words.isEmpty()) {
            return false;
        }
        Instant since = now.minus(WINDOW);
        return prompts.stream()
                .anyMatch(prompt -> !prompt.at().isBefore(since) && holds(prompt.text(), words));
    }

    /**
     * The session of the newest prompt in the window that holds {@code evidence} as {@link
     * #contains} finds it; empty if no such prompt came with a session.
     */
    public synchronized Optional<Session> session(String evidence, Instant now) {
        String words = evidence.strip();
        if (words.isEmpty()) {
            return Optional.empty();
        }
        Instant since = now.minus(WINDOW);
        for (Iterator<Prompt> newest = prompts.descendingIterator(); newest.hasNext(); ) {
            Prompt prompt = newest.next();
            if (prompt.session() != null
                    && !prompt.at().isBefore(since)
                    && holds(prompt.text(), words)) {
                return Optional.of(prompt.session());
            }
        }
        return Optional.empty();
    }

    /**
     * {@code words} appear in {@code text} as whole tokens: 10.0.0.1 is not in "10.0.0.15", 543 not
     * in "5432/tcp", db not in "db-prod". Also the value-in-evidence check of kb_remember.
     */
    public static boolean holds(String text, String words) {
        for (int at = text.indexOf(words); at >= 0; at = text.indexOf(words, at + 1)) {
            if (endsToken(text, at - 1, -1) && endsToken(text, at + words.length(), 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The character at {@code i}, next to a match, ends the token: not a letter, digit, '_' or '-',
     * and not a '.' with a letter or digit beyond it (a sentence end, not "x.y").
     */
    private static boolean endsToken(String text, int i, int away) {
        if (i < 0 || i >= text.length()) {
            return true;
        }
        char next = text.charAt(i);
        if (next == '.') {
            int beyond = i + away;
            return beyond < 0
                    || beyond >= text.length()
                    || !Character.isLetterOrDigit(text.charAt(beyond));
        }
        return !Character.isLetterOrDigit(next) && next != '_' && next != '-';
    }
}
