package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.ismetdyn.agentkb.client.ServerText;

/** What a key means as text or as a move, and text as one screen line, the same in every focus. */
final class Keys {

    /** The key token of Enter in the command table. */
    static final String ENTER = "↵";

    static final int PAGE = 10;

    private Keys() {}

    /** {@code ↵} for Enter, the character of a plain key, else empty. */
    static String token(KeyEvent key) {
        return key.isKey(KeyCode.ENTER) ? ENTER : text(key);
    }

    /**
     * The text a key types: a character without Ctrl or Alt, never a control character. A Turkish
     * letter is text like any other.
     */
    static String text(KeyEvent key) {
        if (key.code() != KeyCode.CHAR || key.hasCtrl() || key.hasAlt()) {
            return "";
        }
        return printable(key.string());
    }

    /** How far a list or detail key moves: arrows and j/k one line, PgUp/PgDn a page, else 0. */
    static int step(KeyEvent key) {
        if (key.isUp() || key.isChar('k')) {
            return -1;
        }
        if (key.isDown() || key.isChar('j')) {
            return 1;
        }
        if (key.isPageUp()) {
            return -PAGE;
        }
        return key.isPageDown() ? PAGE : 0;
    }

    /**
     * Typed or pasted text as a field takes it: without the control and bidi characters kb strips
     * from server text (ServerText), on one line.
     */
    static String printable(String text) {
        return flat((String) ServerText.printable(text));
    }

    /**
     * {@code text} as one screen line: a line break or a tab is a space. Only for drawing; a value
     * is sent as it came.
     */
    static String flat(String text) {
        return text.replaceAll("\\R|\\t", " ");
    }

    /** {@code text} without its last code point, so a Turkish or surrogate pair goes whole. */
    static String dropLast(String text) {
        return text.isEmpty()
                ? text
                : text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }

    /** ASCII case ignored, as the palette's ids and labels and the schema's names are English. */
    static boolean contains(String text, String part) {
        for (int i = 0; i + part.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, part, 0, part.length())) {
                return true;
            }
        }
        return false;
    }
}
