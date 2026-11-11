package io.github.ismetdyn.agentkb.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * What kb puts into a request to the server as text: a JSON string for a body, a path segment and a
 * query. Each one keeps typed text to its own place, so it cannot add a field or reach another
 * route.
 */
final class RequestText {

    private static final int FIRST_PRINTABLE = 0x20;

    private RequestText() {}

    /** {@code text} as a JSON string, so a value with a quote cannot add a field to a body. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < FIRST_PRINTABLE) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * {@code text} as one path segment: a slash, question mark or space in an id cannot reach
     * another route.
     *
     * @throws KbException for {@code .} and {@code ..}: a dot segment moves the path even encoded,
     *     because the server decodes %2E before it resolves the path
     */
    static String segment(String text) {
        if (".".equals(text) || "..".equals(text)) {
            throw new KbException("\"" + text + "\" is not an id");
        }
        return encoded(text);
    }

    /**
     * {@code path?name=value&…} from name, value pairs; a pair with a null value is left out, so
     * the server's default holds.
     */
    static String query(String path, @Nullable Object... namesAndValues) {
        StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                query.add(namesAndValues[i] + "=" + encoded(String.valueOf(namesAndValues[i + 1])));
            }
        }
        return path + query;
    }

    /** Percent-encoded UTF-8; a space is %20 in a path and a query alike. */
    private static String encoded(String text) {
        // URLEncoder writes a space as "+", which a path keeps as a plus; a real plus is %2B.
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
