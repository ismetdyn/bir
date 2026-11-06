package io.github.ismetdyn.agentkb.server.write;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * ARCHITECTURE §4: facts, edges, proposals and signals have ULID ids, 48 bits of milliseconds and
 * 80 random bits in Crockford base32, so ids sort by creation time as in seed/.
 */
final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int BITS_PER_CHAR = 5;
    private static final int TIME_CHARS = 10;
    private static final int RANDOM_HALF_CHARS = 8;
    private static final int RANDOM_HALF_BITS = RANDOM_HALF_CHARS * BITS_PER_CHAR;
    private static final long RANDOM_HALF_MASK = (1L << RANDOM_HALF_BITS) - 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    static String next() {
        return of(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    /**
     * The same id on every machine for the same {@code at} and {@code name}: the 80 bits come from
     * the name, so a record two machines open independently merges into one row (SyncSettle).
     */
    static String derived(Instant at, String name) {
        // Not for secrecy: the name UUID (MD5) only spreads the name over the bits.
        UUID hash = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return of(
                at.toEpochMilli(),
                hash.getMostSignificantBits() >>> (Long.SIZE - RANDOM_HALF_BITS),
                hash.getLeastSignificantBits());
    }

    private static String of(long millis, long high, long low) {
        StringBuilder id = new StringBuilder(TIME_CHARS + 2 * RANDOM_HALF_CHARS);
        append(id, millis, TIME_CHARS);
        append(id, high & RANDOM_HALF_MASK, RANDOM_HALF_CHARS);
        append(id, low & RANDOM_HALF_MASK, RANDOM_HALF_CHARS);
        return id.toString();
    }

    private static void append(StringBuilder id, long value, int chars) {
        for (int shift = (chars - 1) * BITS_PER_CHAR; shift >= 0; shift -= BITS_PER_CHAR) {
            id.append(ALPHABET[(int) ((value >>> shift) & (ALPHABET.length - 1))]);
        }
    }
}
