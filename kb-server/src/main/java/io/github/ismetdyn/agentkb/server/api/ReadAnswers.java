package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.render.EntityView;
import io.github.ismetdyn.agentkb.core.render.FactView;
import io.github.ismetdyn.agentkb.core.render.RelationView;
import io.github.ismetdyn.agentkb.core.render.ResultList;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** The read results as kb-api records (ADR-0025 decision 6); see ApiAnswers for the writes. */
final class ReadAnswers {

    private ReadAnswers() {}

    /**
     * In the result-list order (current, history, raw; invariant 12), the first {@code limit}; each
     * snippet one line of at most 300 characters, as kb_search shows it.
     */
    static SearchAnswer hits(List<Hit> hits, int limit) {
        RequestChecks.Page<Hit> page =
                RequestChecks.page(hits.stream().sorted(ResultList.ORDER).toList(), limit);
        return new SearchAnswer(
                page.items().stream()
                        .map(
                                hit ->
                                        new SearchAnswer.Hit(
                                                hit.id(),
                                                hit.type(),
                                                Ids.normalize(hit.tier().name()),
                                                hit.archived(),
                                                hit.snippet() == null
                                                        ? null
                                                        : ResultList.snippet(hit.snippet())))
                        .toList(),
                page.more());
    }

    /**
     * The schema files as kb_schema reads them; a fact key's rules as the write path applies them.
     */
    static SchemaAnswer schema(Schema schema) {
        return new SchemaAnswer(
                schema.entityTypes().stream()
                        .map(
                                type ->
                                        new SchemaAnswer.EntityType(
                                                type.key(), type.label(), type.operational()))
                        .toList(),
                schema.factKeys().stream()
                        .map(
                                key ->
                                        new SchemaAnswer.FactKey(
                                                key.key(),
                                                key.multi(),
                                                key.rules().get("validator"),
                                                key.allowed(),
                                                key.rules().get("kind"),
                                                key.takesTarget()))
                        .toList(),
                schema.relations().stream()
                        .map(
                                relation ->
                                        new SchemaAnswer.RelationType(
                                                relation.type(),
                                                relation.description(),
                                                relation.synonyms()))
                        .toList());
    }

    static EntityAnswer entity(EntityView view) {
        return new EntityAnswer(
                view.id(),
                view.type(),
                view.status(),
                view.summary(),
                view.aliases(),
                view.facts().stream().map(ReadAnswers::fact).toList(),
                view.relations().stream().map(ReadAnswers::relation).toList(),
                view.secrets().stream()
                        .map(
                                secret ->
                                        new EntityAnswer.Secret(
                                                secret.id(),
                                                secret.name(),
                                                secret.env(),
                                                ApiAnswers.PLACEHOLDER + secret.id()))
                        .toList());
    }

    /**
     * kb_cypher's limits (SPEC §13.5, ADR-0017 decision 4): at most {@code maxRows} rows and {@code
     * maxChars} characters of cells; {@code rows} holds one more if there were more.
     */
    static CypherAnswer cypher(List<Map<String, Object>> rows, int maxRows, int maxChars) {
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
        List<CypherAnswer.Row> shown = new ArrayList<>();
        int chars = 0;
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), maxRows))) {
            List<String> cells = columns.stream().map(column -> text(row.get(column))).toList();
            chars += cells.stream().mapToInt(String::length).sum();
            if (chars > maxChars) {
                break;
            }
            shown.add(new CypherAnswer.Row(cells));
        }
        return new CypherAnswer(columns, shown, shown.size() < rows.size());
    }

    private static EntityAnswer.Fact fact(FactView view) {
        Fact fact = view.fact();
        return new EntityAnswer.Fact(
                fact.id(),
                fact.key(),
                fact.value(),
                fact.env(),
                Ids.normalize(fact.source().name()),
                Ids.normalize(fact.status().name()),
                Times.format(fact.validFrom()),
                Times.format(fact.observedAt()),
                time(fact.validTo()),
                view.target(),
                view.origin());
    }

    private static EntityAnswer.Relation relation(RelationView relation) {
        return new EntityAnswer.Relation(
                relation.id(),
                relation.type(),
                relation.outgoing(),
                relation.other(),
                relation.env(),
                Ids.normalize(relation.source().name()),
                Times.format(relation.validFrom()),
                Times.format(relation.observedAt()),
                time(relation.validTo()));
    }

    private static @Nullable String time(@Nullable Instant time) {
        return time == null ? null : Times.format(time);
    }

    /** A cell as text, as kb_cypher shows it; null is empty. */
    private static String text(@Nullable Object value) {
        return switch (value) {
            case null -> "";
            case Map<?, ?> map ->
                    map.entrySet().stream()
                            .map(entry -> entry.getKey() + ": " + text(entry.getValue()))
                            .collect(Collectors.joining(", ", "{", "}"));
            case List<?> list ->
                    list.stream()
                            .map(ReadAnswers::text)
                            .collect(Collectors.joining(", ", "[", "]"));
            default -> String.valueOf(value);
        };
    }
}
