package io.github.ismetdyn.agentkb.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The one form of a time property in the graph and the snapshot: UTC, whole seconds, {@code
 * 2026-05-10T09:00:00Z}, as in seed/. A fixed length keeps text order equal to time order, which
 * ISO_INSTANT does not (it drops a zero fraction). Read it back with {@link Instant#parse}.
 */
public final class Times {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Times() {}

    public static String format(Instant time) {
        return FORMAT.format(time.truncatedTo(ChronoUnit.SECONDS));
    }
}
