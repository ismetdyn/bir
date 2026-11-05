package io.github.ismetdyn.agentkb.core.render;

import static io.github.ismetdyn.agentkb.core.render.FactLine.ARROW;
import static io.github.ismetdyn.agentkb.core.render.FactLine.CONFLICT;
import static io.github.ismetdyn.agentkb.core.render.FactLine.CURRENT_VALUE;
import static io.github.ismetdyn.agentkb.core.render.FactLine.OLD;
import static io.github.ismetdyn.agentkb.core.render.FactLine.SEP;
import static io.github.ismetdyn.agentkb.core.render.FactLine.UNVERIFIED;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A numbered result list for search and related (SPEC §6.3, ADR-0017): current first, then history,
 * then raw signals; the budget is applied after that order, and what it cuts is counted as {@code
 * (+N sonuç daha)}. Nothing is filtered by time or status (invariant 12).
 */
public final class ResultList {

    /** The order of the tiers is the order of the list. */
    public enum Tier {
        CURRENT,
        HISTORY,
        RAW
    }

    /**
     * One result line, {@code N. id · type[ · arşiv| · ham] — snippet}. A history hit must carry
     * its own mark in the snippet ({@code eski (…)}) unless it is archived.
     */
    public record Hit(
            String id,
            String type,
            Tier tier,
            boolean archived,
            @Nullable String snippet,
            double score) {}

    /** One relation of a path, {@code otherArchived} if the entity it reaches is archived. */
    public record Step(RelationView relation, String otherType, boolean otherArchived) {}

    public static final Comparator<Hit> ORDER =
            Comparator.comparing(Hit::tier)
                    .thenComparing(Hit::score, Comparator.reverseOrder())
                    .thenComparing(Hit::id);

    private static final String ARCHIVED = SEP + "arşiv";
    private static final String RAW = SEP + "ham";
    private static final String DASH = " — ";
    private static final String NONE = "Sonuç yok.";
    private static final String ARCHIVED_STEP = " (arşiv)";
    // The summary write limit (SPEC §5 "Metin sınırları"): an imported record may be longer
    // (invariant 11), and one such line must not use up the budget and hide every other hit.
    private static final int SNIPPET_MAX_CHARS = 300;
    private static final String ELLIPSIS = "…";
    private static final List<String> MARKS = List.of(OLD, CURRENT_VALUE, CONFLICT, UNVERIFIED);
    private static final Pattern BEFORE_SEP = Pattern.compile("(?=" + Pattern.quote(SEP) + ")");
    private static final Pattern BEFORE_ARROW =
            Pattern.compile(
                    "(?=" + Pattern.quote(ARROW) + "|" + Pattern.quote(MarkdownView.BACK) + ")");

    private ResultList() {}

    /**
     * @param limit the most lines to show
     * @param maxChars the most characters the list may take, the more-line included
     */
    public static String render(List<Hit> hits, int limit, int maxChars) {
        if (hits.isEmpty()) {
            return NONE + "\n";
        }
        List<Hit> ordered = hits.stream().sorted(ORDER).toList();
        StringBuilder out = new StringBuilder();
        int shown = 0;
        int reserve = more(ordered.size()).length();
        for (Hit hit : ordered) {
            String line = (shown + 1) + ". " + line(hit) + "\n";
            if (shown == limit || out.length() + line.length() + reserve > maxChars) {
                break;
            }
            out.append(line);
            shown++;
        }
        if (shown < ordered.size()) {
            out.append(more(ordered.size() - shown));
        }
        return out.toString();
    }

    /**
     * A path from {@code startId} as one hit about the record it reaches. It is current only if all
     * its relations are current and none of its records is archived (docs/MCP-TOOLS.md kb_related);
     * a shorter path ranks first.
     */
    public static Hit path(String startId, List<Step> steps) {
        StringBuilder text = new StringBuilder(startId);
        StringBuilder marks = new StringBuilder();
        boolean history = false;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            RelationView relation = step.relation();
            String arrow = relation.outgoing() ? ARROW : MarkdownView.BACK;
            text.append(arrow).append(relation.type()).append(arrow).append(relation.other());
            if (step.otherArchived() && i < steps.size() - 1) {
                text.append(ARCHIVED_STEP);
            }
            if (relation.validTo() != null) {
                marks.append(SEP)
                        .append(OLD)
                        .append(FactLine.range(relation.validFrom(), relation.validTo()))
                        .append(')');
            }
            history |= step.otherArchived() || relation.validTo() != null;
        }
        Step last = steps.getLast();
        return new Hit(
                last.relation().other(),
                last.otherType(),
                history ? Tier.HISTORY : Tier.CURRENT,
                last.otherArchived(),
                text.append(marks).toString(),
                -steps.size());
    }

    private static String line(Hit hit) {
        StringBuilder line = new StringBuilder(hit.id()).append(SEP).append(hit.type());
        if (hit.archived()) {
            line.append(ARCHIVED);
        }
        if (hit.tier() == Tier.RAW) {
            line.append(RAW);
        }
        if (hit.snippet() != null && !hit.snippet().isBlank()) {
            line.append(DASH).append(capped(hit.snippet()));
        }
        return line.toString();
    }

    /**
     * A snippet over the limit loses free text only (a value, a summary, a path's ids); every mark
     * stays whole (invariant 12). Free text, in reading order, fills what the marks leave of the
     * limit; one ellipsis stands for all that is cut.
     */
    private static String capped(String snippet) {
        if (snippet.length() <= SNIPPET_MAX_CHARS) {
            return snippet;
        }
        List<Piece> pieces = pieces(snippet);
        int marksLeft = pieces.stream().filter(Piece::mark).mapToInt(p -> p.text().length()).sum();
        StringBuilder out = new StringBuilder();
        boolean cut = false;
        for (Piece piece : pieces) {
            if (piece.mark()) {
                out.append(piece.text());
                marksLeft -= piece.text().length();
            } else if (!cut) {
                int room = SNIPPET_MAX_CHARS - out.length() - marksLeft;
                if (piece.text().length() <= room) {
                    out.append(piece.text());
                } else {
                    out.append(prefix(piece.text(), Math.max(room, 0))).append(ELLIPSIS);
                    cut = true;
                }
            }
        }
        return out.toString();
    }

    private record Piece(String text, boolean mark) {}

    /**
     * The snippet split before each {@code " · "}; a part that starts with a fact mark is a mark.
     * The other parts are split before each arrow, and a path step ending in {@code (arşiv)} is a
     * mark.
     */
    private static List<Piece> pieces(String snippet) {
        List<Piece> pieces = new ArrayList<>();
        for (String part : BEFORE_SEP.split(snippet)) {
            String body = part.startsWith(SEP) ? part.substring(SEP.length()) : part;
            if (MARKS.stream().anyMatch(body::startsWith)) {
                pieces.add(new Piece(part, true));
                continue;
            }
            for (String step : BEFORE_ARROW.split(part)) {
                pieces.add(new Piece(step, step.endsWith(ARCHIVED_STEP)));
            }
        }
        return pieces;
    }

    private static String prefix(String text, int length) {
        int end = length;
        // Do not split a surrogate pair.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String more(int count) {
        return "(+" + count + " sonuç daha)\n";
    }
}
