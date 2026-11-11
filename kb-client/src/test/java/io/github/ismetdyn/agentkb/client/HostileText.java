package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lead decision 6: the hostile server text the terminal tests send and the check that kb printed it
 * without its control characters (KbServerTextTest, KbReadTextTest).
 */
final class HostileText {

    // JSON string content: ESC[2J clears the screen, ESC]0;…BEL retitles the window, U+202E
    // shows what follows right to left (CWE-451).
    static final String HOSTILE =
            "kbtest-before\\u001b[2J\\u001b]0;kbtest-title\\u0007\\rkbtest-after\\b\\u007f\\u009b31m"
                    + "\\u202ekbtest-reversed";
    // Every C0 control but tab and newline, DEL, the C1 range and the bidi controls.
    static final Pattern CONTROL =
            Pattern.compile(
                    "[\\x00-\\x08\\x0b-\\x1f\\x7f\\x{80}-\\x{9f}\\x{61c}\\x{200e}\\x{200f}"
                            + "\\x{202a}-\\x{202e}\\x{2066}-\\x{2069}]");

    private HostileText() {}

    static void assertStripped(String printed) {
        assertThat(printed)
                .contains("kbtest-before", "kbtest-title", "kbtest-after", "kbtest-reversed");
        assertThat(CONTROL.matcher(printed).find())
                .as("control character in %s", escaped(printed))
                .isFalse();
    }

    /** The printed text with control characters made visible, for a readable failure. */
    static String escaped(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints()
                .forEach(
                        c -> {
                            if (CONTROL.matcher(Character.toString(c)).matches()) {
                                out.append(String.format(Locale.ROOT, "\\u%04x", c));
                            } else {
                                out.appendCodePoint(c);
                            }
                        });
        return out.toString();
    }
}
