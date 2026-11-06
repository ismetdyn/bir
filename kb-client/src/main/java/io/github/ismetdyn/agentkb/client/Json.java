package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the little JSON of the REST answers kb gets (docs/CLIENTS.md §1): objects become maps,
 * arrays lists, numbers BigDecimal, and {@link #toRecord} maps an object to its kb-api record.
 * Hand-rolled, without Jackson or reflection, because Jackson made native kb 55.8 MB and reflection
 * needs native-image configuration (ADR-0025 decision 6). A field the record does not know is
 * ignored; one it needs that is missing, null or of another type is a KbException (exit 1).
 */
final class Json {

    // One entry per kb-api record kb reads; a new record needs its line here (ApiContractTest).
    private static final Map<Class<? extends Record>, Function<Fields, Record>> READERS =
            Map.of(
                    RevertAnswer.class,
                    answer ->
                            new RevertAnswer(
                                    answer.text("batch"),
                                    answer.count("deleted"),
                                    answer.count("reopened"),
                                    answer.count("deletedSecretValues")),
                    RestoreAnswer.class,
                    answer ->
                            new RestoreAnswer(
                                    answer.count("nodes"),
                                    answer.count("edges"),
                                    answer.count("keptSecrets"),
                                    answer.count("keptSignals"),
                                    answer.count("droppedEdges"),
                                    answer.count("droppedSecretValues")),
                    SyncAnswer.class,
                    answer ->
                            new SyncAnswer(
                                    answer.optionalText("commit"),
                                    answer.flag("pushed"),
                                    answer.count("nodes"),
                                    answer.count("edges"),
                                    answer.count("conflicts")),
                    CheckAnswer.class,
                    answer ->
                            new CheckAnswer(
                                    answer.flag("clean"),
                                    answer.objects("problems").stream()
                                            .map(
                                                    problem ->
                                                            new CheckAnswer.Problem(
                                                                    problem.text("kind"),
                                                                    problem.text("id"),
                                                                    problem.text("detail")))
                                            .toList()),
                    MemoryImportAnswer.class,
                    answer ->
                            new MemoryImportAnswer(
                                    answer.count("received"),
                                    answer.count("opened"),
                                    answer.count("truncated")));

    private Json() {}

    /**
     * @throws KbException if {@code text} is not one JSON value
     */
    static Object parse(String text) {
        return new Parser(text).whole();
    }

    /**
     * @throws KbException if {@code text} is not one JSON object that holds every field {@code
     *     type} needs
     */
    static <T extends Record> T read(String text, Class<T> type) {
        return toRecord(parse(text), type);
    }

    /** As {@link #read} for what {@link #parse} returned, e.g. a {@link JsonApi} answer. */
    static <T extends Record> T toRecord(@Nullable Object value, Class<T> type) {
        Function<Fields, Record> reader = READERS.get(type);
        if (reader == null) {
            throw new IllegalArgumentException("Json has no mapping for " + type.getName());
        }
        return type.cast(reader.apply(Fields.of(value, type.getSimpleName())));
    }

    /** The fields of one answer object of kb-api record {@code type}, read by name and kind. */
    private record Fields(Map<?, ?> map, String type) {

        static Fields of(@Nullable Object value, String type) {
            if (value instanceof Map<?, ?> map) {
                return new Fields(map, type);
            }
            throw new KbException("the server answered with JSON that is not an object");
        }

        String text(String name) {
            if (map.get(name) instanceof String text) {
                return text;
            }
            throw missing(name, "text");
        }

        @Nullable String optionalText(String name) {
            Object value = map.get(name);
            return value == null ? null : text(name);
        }

        int count(String name) {
            if (map.get(name) instanceof BigDecimal number) {
                try {
                    return number.intValueExact();
                } catch (ArithmeticException e) {
                    throw missing(name, "whole number");
                }
            }
            throw missing(name, "whole number");
        }

        boolean flag(String name) {
            if (map.get(name) instanceof Boolean flag) {
                return flag;
            }
            throw missing(name, "true or false");
        }

        List<Fields> objects(String name) {
            if (map.get(name) instanceof List<?> list) {
                List<Fields> objects = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> object)) {
                        throw missing(name, "list of objects");
                    }
                    objects.add(new Fields(object, type));
                }
                return objects;
            }
            throw missing(name, "list of objects");
        }

        /** Names the field and the record, never the value the server sent. */
        private KbException missing(String name, String kind) {
            return new KbException(
                    "the server's answer lacks the field "
                            + name
                            + " ("
                            + kind
                            + ") of "
                            + type
                            + "; are kb and the server of the same version?");
        }
    }

    /** One pass over one JSON text. */
    private static final class Parser {

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

        Parser(String text) {
            this.text = text;
        }

        Object whole() {
            Object value = value();
            if (peek() != END || at != text.length()) {
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
}
