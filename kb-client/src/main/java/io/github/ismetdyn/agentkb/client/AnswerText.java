package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.io.PrintWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The plain text of the answers more than one H6 command prints: a hit list (search, stale), a
 * write (add, fact set, rel add), an edit (every close, archive and review) and the count a list
 * left out. Columns are two spaces apart; the server's text is already without control characters
 * (ServerText). A line break in server text must not start a line that looks like a record (H6
 * review): a one-line field is printed on one line, a multi-line field indented.
 */
final class AnswerText {

    static final String COLUMNS = "  ";
    private static final String INDENT = "  ";

    private AnswerText() {}

    /** One line per hit: id, type, tier (archived marked) and the snippet if there is one. */
    static void hits(PrintWriter out, SearchAnswer answer, String none) {
        if (answer.hits().isEmpty() && answer.more() == 0) {
            out.println(none);
            return;
        }
        for (SearchAnswer.Hit hit : answer.hits()) {
            String tier = hit.archived() ? hit.tier() + ", archived" : hit.tier();
            out.println(line(hit.id(), hit.type(), tier, hit.snippet()));
        }
        more(out, answer.more());
    }

    /**
     * The entity, its batch and the ids each list of the write holds; an empty list is left out.
     */
    static void written(PrintWriter out, WriteAnswer answer) {
        out.println(
                oneLine(
                        answer.entity()
                                + (answer.created() ? ": new entity, " : ": known entity, ")
                                + batch(answer.batch())));
        items(out, "added", answer.added());
        items(out, "refreshed", answer.refreshed());
        items(out, "closed", answer.closed());
        items(out, "waiting as a conflict", answer.conflicts());
    }

    /** {@code done}, then the batch that undoes it. */
    static void edited(PrintWriter out, String done, EditAnswer answer) {
        out.println(oneLine(done + "; " + batch(answer.batch())));
    }

    /** How many a list left out past its limit; nothing if none. */
    static void more(PrintWriter out, int more) {
        if (more > 0) {
            out.println(more + " more not shown");
        }
    }

    /** The columns that are there, two spaces apart, on one line. */
    static String line(@Nullable String... columns) {
        StringBuilder line = new StringBuilder();
        for (String column : columns) {
            if (column != null) {
                line.append(line.isEmpty() ? "" : COLUMNS).append(oneLine(column));
            }
        }
        return line.toString();
    }

    /** {@code text} with every line break a space. */
    static String oneLine(String text) {
        return text.replaceAll("\\R", " ");
    }

    /** Every line of {@code text} indented, so none starts where a record line does. */
    static void indented(PrintWriter out, String text) {
        text.lines().forEach(line -> out.println(line.isEmpty() ? line : INDENT + line));
    }

    private static String batch(String batch) {
        return "batch " + batch + " (kb revert --batch " + batch + " undoes it)";
    }

    /** {@code what: a, b} for a list that is not empty; nothing for an empty one. */
    static void items(PrintWriter out, String what, List<String> items) {
        if (!items.isEmpty()) {
            out.println(oneLine(what + ": " + String.join(", ", items)));
        }
    }
}
