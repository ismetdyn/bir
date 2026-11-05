package io.github.ismetdyn.agentkb.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the little JSON of the REST answers kb gets (docs/CLIENTS.md §1): objects become maps,
 * arrays lists, numbers BigDecimal. Hand-rolled because kb-client has no JSON library (CLAUDE.md:
 * no new dependency without asking); the kb-api DTOs of F3 (ADR-0022) replace it.
 */
final class Json {

    private static final Pattern SCALAR =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?|true|false|null");
    private static final String MALFORMED = "the server answered with JSON kb cannot read";
    private static final char END = '\0';
    private static final int UNICODE_DIGITS = 4;
    // The letter after a backslash, and at the same index the character it stands for.
    private static final String ESCAPES = "\"\\/bfnrt";
    private static final String ESCAPED = "\"\\/\b\f\n\r\t";

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @throws KbException if {@code text} is not one JSON value
     */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        if (json.peek() != END || json.at != text.length()) {
            throw new KbException(MALFORMED);
        }
        return value;
    }

    private Object value() {
        return switch (peek()) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            default -> scalar();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        if (take('}')) {
            return map;
        }
        do {
            peek();
            String key = string();
            expect(':');
            map.put(key, value());
        } while (take(','));
        expect('}');
        return map;
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        if (take(']')) {
            return list;
        }
        do {
            list.add(value());
        } while (take(','));
        expect(']');
        return list;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            out.append(c == '\\' ? escaped() : c);
        }
        throw new KbException(MALFORMED);
    }

    private char escaped() {
        char c = at < text.length() ? text.charAt(at++) : END;
        int simple = ESCAPES.indexOf(c);
        if (simple >= 0) {
            return ESCAPED.charAt(simple);
        }
        if (c != 'u') {
            throw new KbException(MALFORMED);
        }
        try {
            char unicode = (char) HexFormat.fromHexDigits(text, at, at + UNICODE_DIGITS);
            at += UNICODE_DIGITS;
            return unicode;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new KbException(MALFORMED);
        }
    }

    /** A number, true, false or null (Java null). */
    private Object scalar() {
        Matcher scalar = SCALAR.matcher(text).region(at, text.length());
        if (!scalar.lookingAt()) {
            throw new KbException(MALFORMED);
        }
        at = scalar.end();
        return switch (scalar.group()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            default -> new BigDecimal(scalar.group());
        };
    }

    /** The next character after white space, {@link #END} at the end of the text. */
    private char peek() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at < text.length() ? text.charAt(at) : END;
    }

    private boolean take(char c) {
        if (peek() == c && at < text.length()) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw new KbException(MALFORMED);
        }
    }
}
