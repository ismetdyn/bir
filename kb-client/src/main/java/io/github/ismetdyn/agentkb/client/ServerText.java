package io.github.ismetdyn.agentkb.client;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the server said, made fit to print (lead decision 6): JsonApi and SecretApi pass every
 * answer and error through it before kb prints a character of it; kb tui cleans typed and pasted
 * text with it too.
 */
public final class ServerText {

    // Every C0 control but tab and newline, DEL and the C1 range (U+009B is a CSI on many
    // terminals), and the bidi controls that reorder what follows (CWE-451): ALM, LRM, RLM, the
    // embeddings and overrides U+202A-U+202E and the isolates U+2066-U+2069.
    private static final Pattern CONTROL =
            Pattern.compile(
                    "[\\x00-\\x08\\x0b-\\x1f\\x7f-\\x9f\\x{61c}\\x{200e}\\x{200f}"
                            + "\\x{202a}-\\x{202e}\\x{2066}-\\x{2069}]");
    private static final int SUCCESS_CLASS = 2;
    private static final int STATUS_CLASS = 100;

    private ServerText() {}

    /**
     * Server text as kb may print it: strings without terminal control characters, so a record or
     * an error cannot move the cursor, clear the screen or retitle the window.
     */
    public static Object printable(Object value) {
        return switch (value) {
            case String text -> CONTROL.matcher(text).replaceAll("");
            case Map<?, ?> map -> {
                Map<Object, Object> clean = new LinkedHashMap<>();
                map.forEach((key, item) -> clean.put(key, printable(item)));
                yield clean;
            }
            case List<?> list -> list.stream().map(ServerText::printable).toList();
            case null, default -> value;
        };
    }

    /** {@code reply} if it is 2xx; else the server's error and hint as exit 1. */
    static HttpResponse<String> success(HttpResponse<String> reply) {
        if (reply.statusCode() / STATUS_CLASS != SUCCESS_CLASS) {
            throw new KbException(
                    "the server answered " + reply.statusCode() + ": " + printable(error(reply)));
        }
        return reply;
    }

    /** {@code error; hint} of a refusal, else the body as it came. */
    static String error(HttpResponse<String> reply) {
        try {
            if (Json.parse(reply.body()) instanceof Map<?, ?> body
                    && body.get("error") instanceof String error) {
                return body.get("hint") instanceof String hint ? error + "; " + hint : error;
            }
        } catch (KbException e) {
            // Not JSON (a proxy page, an empty 404): shown as it came.
        }
        return reply.body().strip();
    }
}
