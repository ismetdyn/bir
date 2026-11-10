package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * The limits of the REST boundary (security rule: every entry point has a size and page limit) and
 * its translations of what the client got wrong. A value past one is refused with 400 {@code
 * {error, hint}} that names the limit, never the value (invariant 10). Controllers hold no
 * try/catch; the two translations of a client error live here.
 */
final class RequestChecks {

    /** The page of a long list and how many it left out. */
    record Page<T>(List<T> items, int more) {}

    // A batch is a ULID (26) the server made; 64 leaves room for a test or tool prefix.
    private static final int BATCH_LIMIT = 64;
    private static final Pattern BATCH = Pattern.compile("[A-Za-z0-9._:-]{0," + BATCH_LIMIT + "}");
    // 9 digits stay below Integer.MAX_VALUE, so parsing cannot overflow.
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,9}");

    // CLIENTS §1 activity: 50 by default, 500 at most; the other lists take the same.
    private static final int LIST_DEFAULT = 50;
    private static final int LIST_MAX = 500;

    private static final Logger LOG = LoggerFactory.getLogger(RequestChecks.class);

    private RequestChecks() {}

    /**
     * {@code time} as the graph writes times (Times.format), so the two compare as text; null if
     * absent.
     *
     * @throws WriteRejectedException if it is no ISO-8601 time
     */
    static @Nullable String time(@Nullable String time, String name) {
        if (time == null) {
            return null;
        }
        try {
            return Times.format(Instant.parse(time));
        } catch (DateTimeParseException e) {
            throw new WriteRejectedException(
                    name + " is not an ISO-8601 time",
                    "Give " + name + " as e.g. 2026-11-01T00:00:00Z.");
        }
    }

    /**
     * Runs a query the user wrote and CypherGuard let through. One that fails or runs over 5 s is
     * the user's error, as in kb_cypher; the log names the exception class only, never the query
     * (invariant 10).
     *
     * @throws WriteRejectedException if it failed
     */
    static <T> T userQuery(Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            LOG.warn("event=rest_cypher_failed error={}", e.getClass().getName());
            throw new WriteRejectedException(
                    "the query failed or ran over 5 s",
                    "Add LIMIT, narrow the pattern, and write values in place of $parameters.");
        }
    }

    /**
     * The limit of a list endpoint: 50 if absent, 1 to 500.
     *
     * @throws WriteRejectedException if it is outside that
     */
    static int listLimit(@Nullable String limit) {
        return number(limit, "limit", LIST_DEFAULT, 1, LIST_MAX);
    }

    /**
     * @throws WriteRejectedException if {@code batch} is no batch id
     */
    static @Nullable String batch(@Nullable String batch) {
        if (batch != null && !BATCH.matcher(batch).matches()) {
            throw new WriteRejectedException(
                    "the batch is not a batch id (at most "
                            + BATCH_LIMIT
                            + " letters, digits, '.', '_', ':' or '-')",
                    "Give the batch id a write returned, e.g. kb revert --batch <id>.");
        }
        return batch;
    }

    /**
     * {@code value} as a whole number from {@code min} to {@code max}; {@code fallback} if absent.
     *
     * @throws WriteRejectedException if it is no number in that range
     */
    static int number(@Nullable String value, String name, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        int number = NUMBER.matcher(value).matches() ? Integer.parseInt(value) : min - 1;
        if (number < min || number > max) {
            throw new WriteRejectedException(
                    name + " is not a whole number from " + min + " to " + max,
                    "Leave "
                            + name
                            + " out for "
                            + fallback
                            + ", or give "
                            + min
                            + " to "
                            + max
                            + ".");
        }
        return number;
    }

    /**
     * {@code value} stripped, or null if absent or blank.
     *
     * @throws WriteRejectedException if it is longer than {@code limit} or holds a control
     *     character
     */
    static @Nullable String word(@Nullable String value, String name, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new WriteRejectedException(
                    name + " is longer than " + limit + " characters or holds a control character",
                    "Give a shorter " + name + " on one line.");
        }
        return value.strip();
    }

    /** The first {@code limit} of {@code all} and how many it left out. */
    static <T> Page<T> page(List<T> all, int limit) {
        return new Page<>(
                all.subList(0, Math.min(limit, all.size())), Math.max(0, all.size() - limit));
    }

    /**
     * ADR-0025 decision 13: Markdown only if the Accept header names {@code text/markdown} with a
     * quality above 0 and at least that of any type JSON would satisfy (RFC 9110 §12.5.1); without
     * one, JSON.
     *
     * @throws WriteRejectedException if the header is no list of media types
     */
    static boolean wantsMarkdown(@Nullable String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException e) {
            throw new WriteRejectedException(
                    "the Accept header is not a list of media types",
                    "Send Accept: text/markdown or Accept: application/json.");
        }
        double markdown =
                quality(types, type -> type.equalsTypeAndSubtype(MediaType.TEXT_MARKDOWN));
        double json = quality(types, type -> type.includes(MediaType.APPLICATION_JSON));
        return markdown > 0 && markdown >= json;
    }

    private static double quality(List<MediaType> types, Predicate<MediaType> matching) {
        return types.stream()
                .filter(matching)
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(0);
    }
}
