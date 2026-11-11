package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.BatchAnswer;
import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.DoctorAnswer;
import io.github.ismetdyn.agentkb.api.DocumentAnswer;
import io.github.ismetdyn.agentkb.api.DocumentListAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
    // The larger F3 records are read in AnswerReaders and DocumentReaders, so this class stays a
    // parser.
    private static final Map<Class<? extends Record>, Function<Fields, Record>> READERS =
            Map.ofEntries(
                    reader(
                            RevertAnswer.class,
                            answer ->
                                    new RevertAnswer(
                                            answer.text("batch"),
                                            answer.count("deleted"),
                                            answer.count("reopened"),
                                            answer.count("deletedSecretValues"))),
                    reader(
                            RestoreAnswer.class,
                            answer ->
                                    new RestoreAnswer(
                                            answer.count("nodes"),
                                            answer.count("edges"),
                                            answer.count("keptSecrets"),
                                            answer.count("keptSignals"),
                                            answer.count("droppedEdges"),
                                            answer.count("droppedSecretValues"))),
                    reader(
                            SyncAnswer.class,
                            answer ->
                                    new SyncAnswer(
                                            answer.optionalText("commit"),
                                            answer.flag("pushed"),
                                            answer.count("nodes"),
                                            answer.count("edges"),
                                            answer.count("conflicts"))),
                    reader(
                            CheckAnswer.class,
                            answer ->
                                    new CheckAnswer(
                                            answer.flag("clean"),
                                            answer.list(
                                                    "problems",
                                                    problem ->
                                                            new CheckAnswer.Problem(
                                                                    problem.text("kind"),
                                                                    problem.text("id"),
                                                                    problem.text("detail"))))),
                    reader(
                            MemoryImportAnswer.class,
                            answer ->
                                    new MemoryImportAnswer(
                                            answer.count("received"),
                                            answer.count("opened"),
                                            answer.count("truncated"))),
                    reader(SearchAnswer.class, AnswerReaders::search),
                    reader(EntityAnswer.class, AnswerReaders::entity),
                    reader(WriteAnswer.class, AnswerReaders::write),
                    reader(EditAnswer.class, answer -> new EditAnswer(answer.text("batch"))),
                    reader(CypherAnswer.class, AnswerReaders::cypher),
                    reader(ConflictsAnswer.class, AnswerReaders::conflicts),
                    reader(ProposalsAnswer.class, AnswerReaders::proposals),
                    reader(ActivityAnswer.class, AnswerReaders::activity),
                    reader(SecretsAnswer.class, AnswerReaders::secrets),
                    reader(ScanAnswer.class, AnswerReaders::scan),
                    reader(SchemaAnswer.class, BrowseReaders::schema),
                    reader(EntityListAnswer.class, BrowseReaders::entities),
                    reader(BatchAnswer.class, BrowseReaders::batch),
                    reader(DocumentAnswer.class, DocumentReaders::document),
                    reader(DocumentListAnswer.class, DocumentReaders::documents),
                    reader(
                            DoctorAnswer.class,
                            answer ->
                                    new DoctorAnswer(
                                            answer.flag("ok"),
                                            answer.list(
                                                    "checks",
                                                    check ->
                                                            new DoctorAnswer.Check(
                                                                    check.text("name"),
                                                                    check.flag("ok"),
                                                                    check.optionalText("hint"))))));

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

    /** One READERS entry; the type parameter keeps a reader to its own record. */
    private static <T extends Record>
            Entry<Class<? extends Record>, Function<Fields, Record>> reader(
                    Class<T> type, Function<Fields, T> read) {
        return Map.entry(type, read::apply);
    }

    /** The fields of one answer object of kb-api record {@code type}, read by name and kind. */
    record Fields(Map<?, ?> map, String type) {

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

        /** A whole number that may be null, e.g. a byte count past the int range. */
        @Nullable Long optionalNumber(String name) {
            Object value = map.get(name);
            if (value == null) {
                return null;
            }
            if (value instanceof BigDecimal number) {
                try {
                    return number.longValueExact();
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

        /** A list of text; any other element is a missing field, not a default. */
        List<String> texts(String name) {
            if (map.get(name) instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) {
                return list.stream().map(String.class::cast).toList();
            }
            throw missing(name, "list of text");
        }

        /** Each object of list {@code name} read by {@code read}. */
        <T> List<T> list(String name, Function<Fields, T> read) {
            return objects(name).stream().map(read).toList();
        }

        private List<Fields> objects(String name) {
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
