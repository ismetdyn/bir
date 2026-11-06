package io.github.ismetdyn.agentkb.core.render;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One fact as a line (SPEC §6.6), shared by the record view and search snippets so a fact reads the
 * same everywhere: {@code key: value[ → target][ @env] · source, date[ · origin][ · episode][ ·
 * mark]}.
 */
public final class FactLine {

    static final String SEP = " · ";
    static final String ARROW = " → ";

    // The marks a snippet carries; ResultList keeps them whole when it cuts a snippet.
    static final String CONFLICT = "ÇELİŞKİ";
    static final String UNVERIFIED = "doğrulanmamış";
    static final String OLD = "eski (";
    static final String CURRENT_VALUE = "güncel: ";
    private static final String KEPT = " korunuyor";
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private FactLine() {}

    /**
     * A search snippet for a matched fact: a current one as in the view, a closed one marked {@code
     * eski (from → to)} with the current value next to it (SPEC §6.3 rule 2).
     *
     * @param keptValues the open, non-conflicting values of the same key and env on the same entity
     */
    public static String snippet(FactView view, List<String> keptValues) {
        Fact fact = view.fact();
        if (fact.validTo() == null) {
            return current(view, keptValues);
        }
        String old = head(view) + SEP + OLD + range(fact.validFrom(), fact.validTo()) + ")";
        return keptValues.isEmpty()
                ? old
                : old + SEP + CURRENT_VALUE + String.join(", ", keptValues);
    }

    /** A current fact is dated by observed_at, when it was last seen. */
    static String current(FactView view, List<String> keptValues) {
        Fact fact = view.fact();
        StringBuilder line =
                new StringBuilder(head(view))
                        .append(SEP)
                        .append(source(fact.source()))
                        .append(", ")
                        .append(DATE.format(fact.observedAt()));
        line.append(provenance(view));
        if (fact.status() == FactStatus.CONFLICT) {
            line.append(SEP).append(CONFLICT);
            if (!keptValues.isEmpty()) {
                line.append(" (").append(String.join(", ", keptValues)).append(KEPT).append(')');
            }
        } else if (fact.status() == FactStatus.UNVERIFIED) {
            line.append(SEP).append(UNVERIFIED);
        }
        return line.toString();
    }

    /** A history range starts at valid_from, when the value was first seen (SPEC §6.3). */
    static String closed(FactView view) {
        Fact fact = view.fact();
        return head(view)
                + SEP
                + source(fact.source())
                + ", "
                + range(fact.validFrom(), fact.validTo())
                + provenance(view);
    }

    /** {@code · origin · episode}, each only when known. */
    private static String provenance(FactView view) {
        return (view.origin() == null ? "" : SEP + view.origin())
                + (view.episode() == null ? "" : SEP + view.episode());
    }

    static String range(Instant from, Instant to) {
        return DATE.format(from) + ARROW + DATE.format(to);
    }

    /** "No env" is the default env; it is not worth a marker on every line. */
    static String env(@Nullable String env) {
        return env == null || env.isBlank() || Fact.DEFAULT_ENV.equals(env) ? "" : " @" + env;
    }

    // Ids.normalize, not toLowerCase: CLAUDE.md keeps lower-casing in one place.
    static String source(Source source) {
        return Ids.normalize(source.name());
    }

    private static String head(FactView view) {
        Fact fact = view.fact();
        String target = view.target() == null ? "" : ARROW + view.target();
        return fact.key() + ": " + fact.value() + target + env(fact.env());
    }
}
