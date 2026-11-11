package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.Placeholders;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * The one masking of the values stored on Secrets (invariant 2, docs/SECRETS.md, ADR-0026): every
 * read answer gets its graph text through {@link #row} (ReadRows, kb_cypher's CypherRows), and the
 * writes that keep a raw text (signals, revisions) and the answers of writes, scans and kb check
 * mask with {@link #text}. A write refuses a stored value only once it is stored, so a text written
 * before is still in the graph; reads mask it, the snapshot keeps it as written (invariant 8).
 */
public final class SecretMask {

    /** What stands for a stored value in an answer or a raw signal. */
    public static final String MASK = "****";

    private static final String STORED_VALUES =
            "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN s.secret_value AS v";
    // ADR-0026: a value this long is masked anywhere; a letters-only one of 4 to 7 characters only
    // as a whole token, as CI log masking and secret scanners treat short values. Write refusal and
    // the PUT note use the same (holds).
    private static final int MASKED_ANYWHERE = 8;
    // Columns the server writes itself: the row mappers parse them (Source, FactStatus, Instant,
    // the blob path of a sha256) or they are ULIDs, batch ids, element ids and times. A value
    // inside one would break a read or hide a record id, not leak. An entity id is never one.
    private static final Set<String> SERVER_COLUMNS =
            Set.of(
                    "id",
                    "batch",
                    "rec",
                    "at",
                    "source",
                    "status",
                    "valid_from",
                    "observed_at",
                    "valid_to",
                    "validFrom",
                    "observedAt",
                    "validTo",
                    "sha256");

    private final List<String> values;

    private SecretMask(List<String> values) {
        this.values = values;
    }

    /**
     * Every value stored on this machine, for a check that must refuse or mask them; never for an
     * answer. An empty value is left out, as it is in every text.
     */
    public static List<String> storedValues(Transaction tx) {
        try (Result result = tx.execute(STORED_VALUES)) {
            return result.stream()
                    .map(row -> String.valueOf(row.get("v")))
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    /**
     * The values stored now, as written and normalized (Ids.normalize): an alias or id keeps a
     * value normalized. Read once per transaction.
     */
    public static SecretMask in(Transaction tx) {
        List<String> stored = storedValues(tx);
        return of(
                Stream.concat(stored.stream(), stored.stream().map(Ids::normalize))
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList());
    }

    /** Masks {@code values}; one under Placeholders.MIN_SECRET_CHARS is no secret (ADR-0026). */
    public static SecretMask of(List<String> values) {
        return new SecretMask(
                values.stream()
                        .filter(value -> value.length() >= Placeholders.MIN_SECRET_CHARS)
                        .toList());
    }

    /**
     * {@code text} with every occurrence of every value masked. All occurrences are found in the
     * original text first, then each covered run becomes one mask: replacing one value after
     * another would leave the rest of a second value readable when the two overlap or one holds the
     * other.
     */
    public String text(String text) {
        if (values.isEmpty()) {
            return text;
        }
        boolean[] covered = new boolean[text.length()];
        boolean found = false;
        for (String value : values) {
            found |= cover(text, value, covered);
        }
        if (!found) {
            return text;
        }
        StringBuilder masked = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!covered[i]) {
                masked.append(text.charAt(i));
            } else if (i == 0 || !covered[i - 1]) {
                masked.append(MASK);
            }
        }
        return masked.toString();
    }

    /**
     * Whether {@code text}, as written or normalized, holds a value as {@link #text} masks it: the
     * one test of write refusal and of the PUT note (ADR-0026).
     */
    public boolean holds(String text) {
        String normalized = Ids.normalize(text);
        return !text(text).equals(text) || !text(normalized).equals(normalized);
    }

    // F3: a value of 8 characters or more, or one with a digit or symbol, counts anywhere; a
    // shorter letters-only one only as a whole token.
    private static boolean cover(String text, String value, boolean[] covered) {
        boolean anywhere =
                value.length() >= MASKED_ANYWHERE
                        || !value.codePoints().allMatch(Character::isLetter);
        boolean found = false;
        for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
            int end = at + value.length();
            if (anywhere || isToken(text, at, end)) {
                Arrays.fill(covered, at, end, true);
                found = true;
            }
        }
        return found;
    }

    // A short letters-only value counts only between the start, the end or a character that is no
    // letter or digit: pgadmin in pgadmin-db, not in pgadmin4.
    private static boolean isToken(String text, int start, int end) {
        return (start == 0 || !Character.isLetterOrDigit(text.codePointBefore(start)))
                && (end == text.length() || !Character.isLetterOrDigit(text.codePointAt(end)));
    }

    /** A row of a read query with every text masked, in nested maps and lists too. */
    Map<String, Object> row(Map<String, Object> row) {
        return map(row);
    }

    private Map<String, Object> map(Map<?, ?> map) {
        Map<String, Object> masked = new LinkedHashMap<>();
        map.forEach(
                (key, value) -> {
                    String name = String.valueOf(key);
                    masked.put(name, SERVER_COLUMNS.contains(name) ? value : value(value));
                });
        return masked;
    }

    private Object value(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text(text);
            // A list property read straight from a node comes as a Java array.
            case String[] texts -> Arrays.stream(texts).map(this::text).toList();
            case Map<?, ?> map -> map(map);
            case List<?> list -> {
                List<Object> masked = new ArrayList<>(list.size());
                list.forEach(item -> masked.add(value(item)));
                yield masked;
            }
            default -> value;
        };
    }
}
