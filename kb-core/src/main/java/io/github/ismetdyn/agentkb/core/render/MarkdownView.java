package io.github.ismetdyn.agentkb.core.render;

import static io.github.ismetdyn.agentkb.core.render.FactLine.ARROW;
import static io.github.ismetdyn.agentkb.core.render.FactLine.SEP;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The record view of SPEC §6.6: heading, summary, then Bilgiler, İlişkiler, Secret'lar, Geçmiş.
 * Current facts and relations go to their sections, closed ones to Geçmiş; nothing is dropped
 * (invariant 12). A secret shows its name and placeholder only (invariant 2).
 */
public final class MarkdownView {

    static final String BACK = " ← ";

    private static final String ITEM = "- ";
    private static final String FACTS = "Bilgiler";
    private static final String RELATIONS = "İlişkiler";
    private static final String SECRETS = "Secret'lar";
    private static final String HISTORY = "Geçmiş";
    private static final String ALIASES = "Alias: ";
    private static final String PLACEHOLDER = "kb:secret/";
    private static final String SECRET_USE = "kullan: kb run --file KEY=… -- <komut>";

    private MarkdownView() {}

    public static String render(EntityView view) {
        List<String> lines = new ArrayList<>();
        lines.add("# " + view.id() + SEP + view.type() + SEP + view.status());
        if (view.summary() != null && !view.summary().isBlank()) {
            lines.add(view.summary());
        }
        if (!view.aliases().isEmpty()) {
            lines.add(ALIASES + String.join(", ", view.aliases()));
        }
        section(
                lines,
                FACTS,
                view.facts().stream()
                        .filter(fact -> fact.fact().validTo() == null)
                        .map(fact -> FactLine.current(fact, keptValues(view, fact.fact())))
                        .toList());
        String current =
                view.relations().stream()
                        .filter(relation -> relation.validTo() == null)
                        .map(MarkdownView::relation)
                        .collect(Collectors.joining(SEP));
        section(lines, RELATIONS, current.isEmpty() ? List.of() : List.of(current));
        section(lines, SECRETS, view.secrets().stream().map(MarkdownView::secret).toList());
        section(
                lines,
                HISTORY,
                Stream.concat(
                                view.facts().stream()
                                        .filter(fact -> fact.fact().validTo() != null)
                                        .map(FactLine::closed),
                                view.relations().stream()
                                        .filter(relation -> relation.validTo() != null)
                                        .map(MarkdownView::endedRelation))
                        .toList());
        return String.join("\n", lines) + "\n";
    }

    /** {@code DEPLOYED_ON → ornek-app-01 @prod}; an incoming relation points back with ←. */
    static String relation(RelationView relation) {
        return relation.type()
                + (relation.outgoing() ? ARROW : BACK)
                + relation.other()
                + FactLine.env(relation.env());
    }

    private static String endedRelation(RelationView relation) {
        return relation(relation)
                + SEP
                + FactLine.source(relation.source())
                + ", "
                + FactLine.range(relation.validFrom(), relation.validTo());
    }

    private static String secret(SecretView secret) {
        return secret.name()
                + FactLine.env(secret.env())
                + SEP
                + PLACEHOLDER
                + secret.id()
                + SEP
                + SECRET_USE;
    }

    /** The value a conflict leaves in place: open, not itself a conflict, same key and env. */
    private static List<String> keptValues(EntityView view, Fact conflict) {
        return view.facts().stream()
                .map(FactView::fact)
                .filter(
                        fact ->
                                fact.validTo() == null
                                        && fact.status() != FactStatus.CONFLICT
                                        && !fact.id().equals(conflict.id())
                                        && fact.key().equals(conflict.key())
                                        && fact.env().equals(conflict.env()))
                .map(Fact::value)
                .toList();
    }

    private static void section(List<String> lines, String name, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        lines.add("");
        lines.add("## " + name);
        items.forEach(item -> lines.add(ITEM + item));
    }
}
