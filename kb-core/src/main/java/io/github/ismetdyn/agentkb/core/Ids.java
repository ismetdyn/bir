package io.github.ismetdyn.agentkb.core;

import java.util.Locale;

/** The one place that normalizes ids and aliases; see CLAUDE.md on String.toLowerCase(). */
public final class Ids {

    // Built from its code point: the mark is invisible in a literal.
    private static final String DOTTED_I_IN_ROOT_LOCALE = "i" + (char) 0x0307;

    private Ids() {}

    /** All of İ, I, ı and i become i, so a Turkish word cannot fork an entity by its case. */
    public static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(DOTTED_I_IN_ROOT_LOCALE, "i")
                .replace('ı', 'i');
    }
}
