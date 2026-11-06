package io.github.ismetdyn.agentkb.core.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The document scan (ADR-0025 decision 3) on its own: values stored on Secrets are found in the
 * same pass as the patterns, also across a window boundary and across lines, and UTF-16 text is
 * decoded as UTF-16. The stored value matches no pattern, so only the stored-value search can find
 * it.
 */
class SecretScanTest {

    private static final String STORED = "zzStoredPlainValue7Q2";
    private static final String FILLER = "plain runbook text line\n";
    // A GitHub token shape (SecretPatterns), assembled so this file holds no token literal.
    private static final String TOKEN = "ghp" + "_" + "kbtestFake".repeat(4).substring(0, 36);

    @Test
    void the_stored_value_matches_no_pattern() {
        assertThat(SecretPatterns.looksLikeSecret(STORED)).isFalse();
    }

    @Test
    void a_stored_value_is_found_with_its_line() throws IOException {
        String text = FILLER.repeat(4) + "password is " + STORED + " here\n" + FILLER;

        assertThat(scan(text, StandardCharsets.UTF_8, List.of(STORED))).hasValue(5);
        assertThat(scan(text, StandardCharsets.UTF_8, List.of())).isEmpty();
    }

    /** A line far longer than a window, the value across each window boundary. */
    @ParameterizedTest
    @ValueSource(ints = {SecretScan.WINDOW, 2 * SecretScan.WINDOW, 3 * SecretScan.WINDOW})
    void a_stored_value_across_a_window_boundary_is_found(int boundary) throws IOException {
        String text =
                "first\n" + "x".repeat(boundary - 6 - 5) + STORED + "y".repeat(SecretScan.WINDOW);

        assertThat(scan(text, StandardCharsets.UTF_8, List.of(STORED))).hasValue(2);
    }

    /** A stored value longer than the overlap of the pattern windows is still found whole. */
    @Test
    void a_stored_value_longer_than_the_pattern_overlap_is_found() throws IOException {
        String longValue = "zzLong".repeat(SecretScan.OVERLAP);
        String text = "first\n" + "x".repeat(SecretScan.WINDOW - 100) + longValue + "\n";

        assertThat(scan(text, StandardCharsets.UTF_8, List.of(longValue))).hasValue(2);
    }

    /** A value with a line break (a key file) is found, at the line it starts on. */
    @Test
    void a_stored_value_across_lines_is_found_at_its_first_line() throws IOException {
        String multiLine = "zzPartOne7Q2\nzzPartTwo7Q2";
        String text = FILLER.repeat(2) + "key " + multiLine + "\n" + FILLER;

        assertThat(scan(text, StandardCharsets.UTF_8, List.of(multiLine))).hasValue(3);
    }

    @Test
    void the_earlier_of_a_pattern_and_a_stored_value_is_named() throws IOException {
        String text = FILLER + STORED + "\n" + FILLER + "token " + TOKEN + "\n";

        assertThat(scan(text, StandardCharsets.UTF_8, List.of(STORED))).hasValue(2);
    }

    /**
     * UTF-16 text with a byte order mark is scanned as UTF-16, and as UTF-8 too: an ASCII line
     * appended to it (PowerShell {@code >}, then {@code Add-Content}) is only readable as UTF-8.
     */
    @Test
    void utf16_text_with_a_byte_order_mark_is_scanned() throws IOException {
        String text = FILLER + "token " + TOKEN + "\n";

        assertThat(scan(text, StandardCharsets.UTF_16, List.of())).hasValue(2);
        assertThat(scan("﻿" + text, StandardCharsets.UTF_16LE, List.of())).hasValue(2);

        byte[] tail = ("token=" + TOKEN + "\r\n").getBytes(StandardCharsets.US_ASCII);
        for (Charset utf16 : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] head = ("﻿" + FILLER).getBytes(utf16);
            byte[] bytes = Arrays.copyOf(head, head.length + tail.length);
            System.arraycopy(tail, 0, bytes, head.length, tail.length);

            assertThat(SecretScan.firstSecretLine(new ByteArrayInputStream(bytes), List.of()))
                    .as("BOM'd %s with an ASCII tail", utf16)
                    .hasValue(2);
        }
    }

    /**
     * Without a byte order mark, NUL-heavy bytes are scanned both as UTF-16 and as UTF-8: UTF-16
     * text is found, and so is the ASCII in a binary full of NUL, which only UTF-8 can read.
     */
    @Test
    void nul_heavy_bytes_without_a_byte_order_mark_are_scanned_as_utf16_and_utf8()
            throws IOException {
        String text = FILLER + "token " + TOKEN + "\n" + STORED + "\n";

        assertThat(scan(text, StandardCharsets.UTF_16LE, List.of())).hasValue(2);
        assertThat(scan(text, StandardCharsets.UTF_16BE, List.of())).hasValue(2);
        assertThat(scan(FILLER + STORED, StandardCharsets.UTF_16LE, List.of(STORED))).hasValue(2);

        String nul = "\0".repeat(2 * SecretScan.SNIFF_BYTES);
        String tokenText = nul + "\ntoken " + TOKEN + "\n" + nul;
        String storedText = nul + "\nkey " + STORED + "\n" + nul;

        assertThat(scan(tokenText, StandardCharsets.UTF_8, List.of())).hasValue(2);
        assertThat(scan(storedText, StandardCharsets.UTF_8, List.of(STORED))).hasValue(2);
        assertThat(scan(storedText, StandardCharsets.UTF_8, List.of())).isEmpty();
    }

    /** UTF-8 text with a few NUL bytes stays UTF-8. */
    @Test
    void text_with_few_nul_bytes_is_scanned_as_utf8() throws IOException {
        String text = "a\0b\0" + FILLER.repeat(3) + "token " + TOKEN + "\n";

        assertThat(scan(text, StandardCharsets.UTF_8, List.of())).hasValue(4);
    }

    private static OptionalLong scan(String text, Charset charset, List<String> stored)
            throws IOException {
        return SecretScan.firstSecretLine(new ByteArrayInputStream(text.getBytes(charset)), stored);
    }
}
