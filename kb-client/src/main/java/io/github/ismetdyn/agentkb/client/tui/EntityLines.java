package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.api.EntityAnswer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The detail of an entity as lines (docs/CLIENTS.md §3): facts, secrets by name and placeholder
 * only, relations out and in. Nothing is left out for its time or status (invariant 12): a closed
 * fact, an ended relation and an archived entity are shown, faded and marked. Every server text is
 * one line (Keys.flat); JsonApi already took out the control characters.
 */
final class EntityLines {

    /** {@code alert} for what needs the user: a conflict or an archived entity. */
    record Line(String text, boolean faded, boolean alert) {

        Line {
            text = Keys.flat(text);
        }

        static Line plain(String text) {
            return new Line(text, false, false);
        }
    }

    static final String ARCHIVED = "archived";
    private static final String OK = "ok";
    private static final int DATE = "2026-01-01".length();

    private EntityLines() {}

    static List<Line> of(EntityAnswer entity) {
        List<Line> lines = new ArrayList<>();
        boolean archived = ARCHIVED.equals(entity.status());
        String status = archived ? "ARCHIVED" : entity.status();
        lines.add(new Line(entity.id() + " (" + entity.type() + ")  " + status, false, archived));
        if (entity.summary() != null) {
            lines.add(Line.plain(entity.summary()));
        }
        if (!entity.aliases().isEmpty()) {
            lines.add(Line.plain("aliases: " + String.join(", ", entity.aliases())));
        }
        lines.add(Line.plain(entity.facts().isEmpty() ? "Facts: none" : "Facts"));
        entity.facts().forEach(fact -> lines.add(fact(fact)));
        if (!entity.secrets().isEmpty()) {
            lines.add(Line.plain("Secrets"));
            entity.secrets()
                    .forEach(
                            secret ->
                                    lines.add(
                                            Line.plain(
                                                    "  "
                                                            + secret.name()
                                                            + " @"
                                                            + secret.env()
                                                            + " · "
                                                            + secret.placeholder())));
        }
        lines.add(Line.plain(entity.relations().isEmpty() ? "Relations: none" : "Relations"));
        entity.relations().forEach(relation -> lines.add(relation(relation)));
        return lines;
    }

    /** A closed fact faded with its end; a status other than ok marked, in red while current. */
    private static Line fact(EntityAnswer.Fact fact) {
        boolean closed = fact.validTo() != null;
        boolean marked = !OK.equals(fact.status());
        String target = fact.target() == null ? "" : " → " + fact.target();
        String mark = marked ? "  [" + fact.status() + "]" : "";
        String end = closed ? "  [closed " + date(fact.validTo()) + "]" : "";
        return new Line(
                "  "
                        + fact.key()
                        + " @"
                        + fact.env()
                        + "  "
                        + fact.value()
                        + target
                        + "  "
                        + fact.source()
                        + " "
                        + date(fact.validFrom())
                        + mark
                        + end,
                closed,
                marked && !closed);
    }

    /** Out with →, in with ←; an ended relation faded with its end. */
    private static Line relation(EntityAnswer.Relation relation) {
        boolean ended = relation.validTo() != null;
        String env = relation.env() == null ? "" : " @" + relation.env();
        String end = ended ? "  [ended " + date(relation.validTo()) + "]" : "";
        return new Line(
                "  "
                        + relation.type()
                        + (relation.outgoing() ? " → " : " ← ")
                        + relation.other()
                        + env
                        + "  "
                        + relation.source()
                        + " "
                        + date(relation.validFrom())
                        + end,
                ended,
                false);
    }

    /** The day of an ISO-8601 time; a shorter text (an imported record's) as it is. */
    private static String date(@Nullable String time) {
        if (time == null) {
            return "";
        }
        return time.length() > DATE ? time.substring(0, DATE) : time;
    }
}
