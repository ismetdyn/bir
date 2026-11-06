package io.github.ismetdyn.agentkb.core.secret;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

/**
 * The secret scan of a document (ADR-0025 decision 3): the text is checked with {@link
 * SecretPatterns} line by line, so a hit has a line number and no value. A line longer than {@link
 * #WINDOW} characters (a binary file, minified text) is checked in windows that overlap by {@link
 * #OVERLAP} characters, so a token across a window boundary is still whole in one window. Values
 * stored on Secrets are searched in the same pass, across lines, with an overlap of the longest
 * value. Memory stays at a few windows, whatever the size of the document.
 *
 * <p>The bytes are always decoded as UTF-8. They are also decoded as UTF-16, in the same read, when
 * they start with its byte order mark or, without one, when the first {@link #SNIFF_BYTES} hold NUL
 * in at least 90% of the odd (little-endian) or even (big-endian) positions, as ASCII text in
 * UTF-16 does; of the hits in one read the lowest line wins. Invalid bytes are replaced, so binary
 * content does not stop the scan.
 */
public final class SecretScan {

    // Far above any text line; only binary or minified content reaches it.
    static final int WINDOW = 64 * 1024;
    // Far above the longest match SecretPatterns needs (a key header, a name=value, a URL).
    static final int OVERLAP = 4 * 1024;
    static final int SNIFF_BYTES = 4 * 1024;
    private static final int READ_BYTES = 8 * 1024;
    private static final int READ_CHARS = 8 * 1024;
    // More than the bytes of one character in UTF-8 or UTF-16.
    private static final int PENDING_BYTES = 8;
    // At least 90% NUL in one byte position of each pair.
    private static final int NUL_SHARE_PERCENT = 90;
    private static final int PERCENT = 100;

    private SecretScan() {}

    /**
     * Reads {@code bytes} up to the first line that looks like a secret or holds one of {@code
     * stored}, or to their end.
     *
     * @return the 1-based number of that line; empty if there is none
     */
    public static OptionalLong firstSecretLine(InputStream bytes, List<String> stored)
            throws IOException {
        BufferedInputStream in = new BufferedInputStream(bytes);
        in.mark(SNIFF_BYTES);
        List<Pass> passes =
                charsets(in.readNBytes(SNIFF_BYTES)).stream()
                        .map(charset -> new Pass(charset, stored))
                        .toList();
        in.reset();
        byte[] buffer = new byte[READ_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            long found = 0;
            for (Pass pass : passes) {
                found = lower(found, pass.feed(buffer, read));
            }
            if (found > 0) {
                return OptionalLong.of(found);
            }
        }
        long found = 0;
        for (Pass pass : passes) {
            found = lower(found, pass.end());
        }
        return found > 0 ? OptionalLong.of(found) : OptionalLong.empty();
    }

    /** The lower of two hit lines, 0 being no hit. */
    private static long lower(long found, long next) {
        return found == 0 || (next > 0 && next < found) ? next : found;
    }

    /**
     * The charsets to scan {@code head}'s content in: UTF-8 always, after the UTF-16 its byte order
     * mark or, without one, its NUL bytes suggest. UTF-8 stays because ASCII appended to UTF-16
     * text (PowerShell {@code >}, then {@code Add-Content}) or in a binary full of NUL is only
     * readable as UTF-8.
     */
    static List<Charset> charsets(byte[] head) {
        if (startsWith(head, (byte) 0xFF, (byte) 0xFE)) {
            return List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_8);
        }
        if (startsWith(head, (byte) 0xFE, (byte) 0xFF)) {
            return List.of(StandardCharsets.UTF_16BE, StandardCharsets.UTF_8);
        }
        if (mostlyNul(head, 1)) {
            return List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_8);
        }
        if (mostlyNul(head, 0)) {
            return List.of(StandardCharsets.UTF_16BE, StandardCharsets.UTF_8);
        }
        return List.of(StandardCharsets.UTF_8);
    }

    private static boolean startsWith(byte[] head, byte first, byte second) {
        return head.length >= 2 && head[0] == first && head[1] == second;
    }

    /** NUL in at least 90% of the pairs at {@code offset} (0 even, 1 odd) of each byte pair. */
    private static boolean mostlyNul(byte[] head, int offset) {
        int pairs = head.length / 2;
        int nul = 0;
        for (int i = offset; i < pairs * 2; i += 2) {
            nul += head[i] == 0 ? 1 : 0;
        }
        return pairs > 0 && nul * PERCENT >= pairs * NUL_SHARE_PERCENT;
    }

    /**
     * The scan of the bytes in one charset: they are decoded as they come, and each character goes
     * to the line and to the stored-value search. Bytes of a character cut by a read wait for the
     * next one.
     */
    private static final class Pass {

        // Room for a read plus the few bytes of a character a read cut.
        private final ByteBuffer bytes = ByteBuffer.allocate(READ_BYTES + PENDING_BYTES);
        private final CharBuffer chars = CharBuffer.allocate(READ_CHARS);
        private final CharsetDecoder decoder;
        private final Line line = new Line();
        private final Stored values;

        Pass(Charset charset, List<String> stored) {
            this.decoder =
                    charset.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE);
            this.values = new Stored(stored);
        }

        /** Scans {@code length} more bytes; the line of a hit, else 0. */
        long feed(byte[] buffer, int length) {
            bytes.put(buffer, 0, length).flip();
            long found = decode(false);
            bytes.compact();
            return found;
        }

        /** Scans what is left once the bytes end; the line of a hit, else 0. */
        long end() {
            bytes.flip();
            long found = decode(true);
            if (found > 0) {
                return found;
            }
            CoderResult result;
            do {
                result = decoder.flush(chars);
                found = scanChars();
            } while (found == 0 && result.isOverflow());
            if (found > 0) {
                return found;
            }
            return line.looksLikeSecret() ? earlier(line.number) : values.search();
        }

        private long decode(boolean last) {
            CoderResult result;
            do {
                result = decoder.decode(bytes, chars, last);
                long found = scanChars();
                if (found > 0) {
                    return found;
                }
            } while (result.isOverflow());
            return 0;
        }

        private long scanChars() {
            chars.flip();
            try {
                while (chars.hasRemaining()) {
                    char c = chars.get();
                    long found = values.add(c);
                    if (found == 0 && line.add(c)) {
                        found = earlier(line.number);
                    }
                    if (found > 0) {
                        return found;
                    }
                }
                return 0;
            } finally {
                chars.clear();
            }
        }

        /** {@code number}, or the line of a stored value still waiting before it. */
        private long earlier(long number) {
            long found = values.search();
            return found > 0 ? Math.min(found, number) : number;
        }
    }

    /** The current line, or its last window, and its number. */
    private static final class Line {

        private final StringBuilder text = new StringBuilder();
        private long number = 1;

        /** Adds {@code c}; true once the line or window it completes looks like a secret. */
        boolean add(char c) {
            if (c == '\n') {
                if (looksLikeSecret()) {
                    return true;
                }
                text.setLength(0);
                number++;
                return false;
            }
            text.append(c);
            if (text.length() < WINDOW) {
                return false;
            }
            if (looksLikeSecret()) {
                return true;
            }
            text.delete(0, WINDOW - OVERLAP);
            return false;
        }

        boolean looksLikeSecret() {
            return SecretPatterns.looksLikeSecret(text.toString());
        }
    }

    /**
     * The text not yet searched for the stored values, line breaks included, so a value across
     * lines is found too. It is searched once it is full; the last {@code longest - 1} characters
     * stay for the next search, so a value across that boundary is still whole in one search.
     */
    private static final class Stored {

        private final List<String> values;
        private final int keep;
        private final int capacity;
        private final StringBuilder text = new StringBuilder();
        // The line of text's first character.
        private long firstLine = 1;

        Stored(List<String> stored) {
            // An empty value would be found everywhere.
            this.values = stored.stream().filter(value -> !value.isEmpty()).toList();
            int longest = this.values.stream().mapToInt(String::length).max().orElse(0);
            this.keep = Math.max(0, longest - 1);
            this.capacity = Math.max(WINDOW, 2 * longest);
        }

        /** Adds {@code c}; the line of a stored value once a full buffer holds one, else 0. */
        long add(char c) {
            if (values.isEmpty()) {
                return 0;
            }
            text.append(c);
            if (text.length() < capacity) {
                return 0;
            }
            long found = search();
            if (found > 0) {
                return found;
            }
            int cut = text.length() - keep;
            firstLine += newlines(cut);
            text.delete(0, cut);
            return 0;
        }

        /** The line the first stored value in the buffer starts on; 0 if there is none. */
        long search() {
            int first = -1;
            for (String value : values) {
                int at = text.indexOf(value);
                if (at >= 0 && (first < 0 || at < first)) {
                    first = at;
                }
            }
            return first < 0 ? 0 : firstLine + newlines(first);
        }

        private long newlines(int end) {
            long count = 0;
            for (int i = 0; i < end; i++) {
                count += text.charAt(i) == '\n' ? 1 : 0;
            }
            return count;
        }
    }
}
