package io.github.ismetdyn.agentkb.server.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cypher text the way {@link CypherGuard} reads it. {@link #mask} drops comments and the contents
 * of string literals, so a keyword inside them is data and a keyword after them is still seen; the
 * other methods read the masked text.
 */
final class CypherText {

    private static final String EMPTY_STRING = "''";
    // A word before '[' that starts an expression, so the bracket opens a list, not a subscript.
    private static final Set<String> LIST_AFTER =
            Set.of(
                    "RETURN",
                    "WITH",
                    "IN",
                    "UNWIND",
                    "WHERE",
                    "AND",
                    "OR",
                    "XOR",
                    "NOT",
                    "CASE",
                    "WHEN",
                    "THEN",
                    "ELSE",
                    "DISTINCT",
                    "BY",
                    "CONTAINS",
                    "YIELD",
                    "IS");
    private static final String KEYWORDS = "(?:" + String.join("|", LIST_AFTER) + ")";
    // A keyword of LIST_AFTER right before '[': not the end of a longer name (x + combining mark +
    // when), not a property (m.yield) and not a parameter. CASE_INSENSITIVE is ASCII only, as
    // Neo4j matches keywords, so "with" spelled with a dotless i is a name, though it upper-cases
    // to WITH.
    private static final Pattern KEYWORD_BEFORE =
            Pattern.compile(
                    "(?<!\\p{javaUnicodeIdentifierPart})(?<![.$]\\s{0,99})(" + KEYWORDS + ")\\s*$",
                    Pattern.CASE_INSENSITIVE);
    // Neo4j takes a keyword as a variable or map key too (G6 probe): a keyword of LIST_AFTER the
    // query binds (AS when, (when:L), [when IN …], reduce(when = …)) or uses as a value may be
    // subscripted. Over-matching only makes a bracket after that word a subscript.
    private static final Pattern KEYWORD_AS_NAME =
            Pattern.compile(
                    "(?<=\\bAS\\s{1,99})"
                            + KEYWORDS
                            + "\\b|\\b"
                            + KEYWORDS
                            + "(?=\\s*(?:[.:)\\]},=|]|\\b(?:IN|IS|WHERE)\\b))|(?<=[(\\[]\\s{0,99})"
                            + KEYWORDS
                            + "(?=\\s*\\{)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_END = Pattern.compile("[\\r\\n]");
    private static final String SUBSCRIPT_AFTER = ")]}`'";
    // A list index or slice by number: x[0], x[-1], x[1..3], x[..2].
    private static final Pattern NUMERIC_INDEX =
            Pattern.compile("\\s*-?\\d*\\s*(\\.\\.\\s*-?\\d*\\s*)?");
    // [r:TYPE…], [:TYPE…], [r IS TYPE…]; the label expression ends at a property map, a
    // quantifier or an inline WHERE.
    private static final Pattern RELATION_TYPES =
            Pattern.compile(
                    "^\\s*(?:[\\p{L}_][\\p{L}\\p{N}_]*|`[^`]*`)?\\s*(?::|\\bIS\\b)(.*?)(?:[{*]|\\bWHERE\\b|$)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NAME =
            Pattern.compile("`((?:[^`]|``)*)`|[\\p{L}_][\\p{L}\\p{N}_]*|\\$");

    private CypherText() {}

    /** Comments become a space, a string literal becomes {@code ''}; backtick names stay. */
    static String mask(String query) {
        StringBuilder out = new StringBuilder(query.length());
        int i = 0;
        while (i < query.length()) {
            char first = query.charAt(i);
            int end = tokenEnd(query, i);
            if (end == i || first == '`') {
                end = Math.max(end, i + 1);
                out.append(query, i, end);
            } else if (first == '\'' || first == '"') {
                out.append(EMPTY_STRING);
            } else {
                out.append(' ');
            }
            i = end;
        }
        return out.toString();
    }

    /**
     * True if a subscript reads by anything but a number: {@code n['name']}, {@code n[k]}, {@code
     * m[$p]}. That is how a property is read by a computed name (G6 probe).
     */
    static boolean hasDynamicLookup(String masked) {
        Set<String> keywordNames =
                KEYWORD_AS_NAME
                        .matcher(masked)
                        .results()
                        .map(name -> name.group().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        for (int open = masked.indexOf('['); open >= 0; open = masked.indexOf('[', open + 1)) {
            if (isSubscript(masked, open, keywordNames)
                    && !NUMERIC_INDEX.matcher(inside(masked, open)).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The relationship types named in patterns, {@code -[r:A|B]->}, as written. A type named at run
     * time, {@code [:$(…)]}, cannot be checked and comes back as {@code $}, which is no type.
     */
    static List<String> relationTypes(String masked) {
        List<String> types = new ArrayList<>();
        for (int open = masked.indexOf('['); open >= 0; open = masked.indexOf('[', open + 1)) {
            if (previousChar(masked, open) == '-') {
                Matcher pattern = RELATION_TYPES.matcher(inside(masked, open));
                if (pattern.find()) {
                    types.addAll(names(pattern.group(1)));
                }
            }
        }
        return types;
    }

    private static int tokenEnd(String query, int start) {
        char c = query.charAt(start);
        if (query.startsWith("//", start)) {
            // Neo4j ends a line comment at CR as well as LF (G6 probe).
            Matcher end = LINE_END.matcher(query).region(start, query.length());
            return end.find() ? end.start() : query.length();
        }
        if (query.startsWith("/*", start)) {
            int end = query.indexOf("*/", start + 2);
            return end < 0 ? query.length() : end + 2;
        }
        if (c == '\'' || c == '"') {
            return quotedEnd(query, start, c);
        }
        if (c == '`') {
            return backtickEnd(query, start);
        }
        return start;
    }

    // A backslash escapes the next character; an unclosed literal runs to the end.
    private static int quotedEnd(String query, int start, char quote) {
        int i = start + 1;
        while (i < query.length() && query.charAt(i) != quote) {
            i += query.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, query.length());
    }

    // A doubled backtick is an escaped one inside the name.
    private static int backtickEnd(String query, int start) {
        int i = query.indexOf('`', start + 1);
        while (i >= 0 && i + 1 < query.length() && query.charAt(i + 1) == '`') {
            i = query.indexOf('`', i + 2);
        }
        return i < 0 ? query.length() : i + 1;
    }

    // After a name or a closing bracket the bracket is a subscript, after a keyword of LIST_AFTER
    // it opens a list, unless the query also uses that keyword as a name.
    private static boolean isSubscript(String masked, int open, Set<String> keywordNames) {
        char before = previousChar(masked, open);
        if (SUBSCRIPT_AFTER.indexOf(before) >= 0) {
            return true;
        }
        if (!Character.isUnicodeIdentifierPart(before)) {
            return false;
        }
        Matcher keyword = KEYWORD_BEFORE.matcher(masked.substring(0, open));
        return !keyword.find() || keywordNames.contains(keyword.group(1).toUpperCase(Locale.ROOT));
    }

    private static char previousChar(String masked, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(masked.charAt(i))) {
            i--;
        }
        return i < 0 ? ' ' : masked.charAt(i);
    }

    /** The text up to the matching ']', or to the end if there is none. */
    private static String inside(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return masked.substring(open + 1, i);
            }
        }
        return masked.substring(open + 1);
    }

    private static List<String> names(String labelExpression) {
        List<String> names = new ArrayList<>();
        Matcher name = NAME.matcher(labelExpression);
        while (name.find()) {
            names.add(name.group(1) != null ? name.group(1).replace("``", "`") : name.group());
        }
        return names;
    }
}
