package io.github.ismetdyn.agentkb.core.secret;

import java.io.IOException;
import java.io.Reader;
import java.util.OptionalLong;

/**
 * The secret scan of a document (ADR-0025 decision 3): the text is checked with {@link
 * SecretPatterns} line by line, so a hit has a line number and no value. A line longer than {@link
 * #WINDOW} characters (a binary file, minified text) is checked in windows that overlap by {@link
 * #OVERLAP} characters, so a token across a window boundary is still whole in one window. Memory
 * stays at one window, whatever the size of the document.
 */
public final class SecretScan {

    // Far above any text line; only binary or minified content reaches it.
    static final int WINDOW = 64 * 1024;
    // Far above the longest match SecretPatterns needs (a key header, a name=value, a URL).
    static final int OVERLAP = 4 * 1024;
    private static final int READ_CHARS = 8 * 1024;

    private SecretScan() {}

    /**
     * Reads {@code text} up to the first secret-looking line, or to its end.
     *
     * @return the 1-based number of that line; empty if there is none
     */
    public static OptionalLong firstSecretLine(Reader text) throws IOException {
        Line line = new Line();
        char[] buffer = new char[READ_CHARS];
        int read;
        while ((read = text.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                if (line.add(buffer[i])) {
                    return OptionalLong.of(line.number);
                }
            }
        }
        return line.looksLikeSecret() ? OptionalLong.of(line.number) : OptionalLong.empty();
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
}
