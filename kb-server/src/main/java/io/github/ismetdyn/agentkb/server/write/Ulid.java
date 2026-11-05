package io.github.ismetdyn.agentkb.server.write;

import java.security.SecureRandom;

/**
 * ARCHITECTURE §4: facts, edges, proposals and signals have ULID ids, 48 bits of milliseconds and
 * 80 random bits in Crockford base32, so ids sort by creation time as in seed/.
 */
final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int BITS_PER_CHAR = 5;
    private static final int TIME_CHARS = 10;
    private static final int RANDOM_HALF_CHARS = 8;
    private static final long RANDOM_HALF_MASK = (1L << (RANDOM_HALF_CHARS * BITS_PER_CHAR)) - 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    static String next() {
        StringBuilder id = new StringBuilder(TIME_CHARS + 2 * RANDOM_HALF_CHARS);
        append(id, System.currentTimeMillis(), TIME_CHARS);
        append(id, RANDOM.nextLong() & RANDOM_HALF_MASK, RANDOM_HALF_CHARS);
        append(id, RANDOM.nextLong() & RANDOM_HALF_MASK, RANDOM_HALF_CHARS);
        return id.toString();
    }

    private static void append(StringBuilder id, long value, int chars) {
        for (int shift = (chars - 1) * BITS_PER_CHAR; shift >= 0; shift -= BITS_PER_CHAR) {
            id.append(ALPHABET[(int) ((value >>> shift) & (ALPHABET.length - 1))]);
        }
    }
}
