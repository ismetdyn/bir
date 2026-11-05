package io.github.ismetdyn.agentkb.server.graph;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.FactLine;
import io.github.ismetdyn.agentkb.core.render.FactView;
import io.github.ismetdyn.agentkb.core.render.RelationView;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.core.render.ResultList.Step;
import io.github.ismetdyn.agentkb.core.render.ResultList.Tier;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Cypher rows of {@link KnowledgeQueries} to core records. Rows carry only the projected properties
 * the queries name, so a Secret's {@code secret_value} never reaches a mapper.
 */
final class KnowledgeQueriesRows {

    private static final String ARCHIVED = "archived";

    private KnowledgeQueriesRows() {}

    static FactView factView(Map<?, ?> row) {
        return new FactView(
                fact((Map<?, ?>) row.get("fact")), (String) row.get("target"), origin(row));
    }

    static RelationView relation(Map<?, ?> row) {
        return new RelationView(
                (String) row.get("type"),
                (Boolean) row.get("outgoing"),
                (String) row.get("other"),
                (String) row.get("env"),
                Source.valueOf(upper(row.get("source"))),
                Instant.parse((String) row.get("validFrom")),
                Instant.parse((String) row.get("observedAt")),
                instant(row.get("validTo")));
    }

    static Step step(Map<?, ?> row) {
        return new Step(
                relation(row), (String) row.get("otherType"), ARCHIVED.equals(row.get("status")));
    }

    /**
     * A fulltext match as a hit about its record: a fact speaks for the entity that has it. A
     * closed fact or an archived entity is history, a signal is raw (SPEC §6.3).
     */
    static Hit searchHit(Map<?, ?> row) {
        String id = (String) row.get("id");
        String type = (String) row.get("type");
        double score = ((Number) row.get("score")).doubleValue();
        String text = (String) row.get("text");
        if (Boolean.TRUE.equals(row.get("isSignal"))) {
            return new Hit(id, type, Tier.RAW, false, text, score);
        }
        boolean archived =
                Boolean.TRUE.equals(row.get("isEntity")) && ARCHIVED.equals(row.get("status"));
        Map<?, ?> factRow = (Map<?, ?>) row.get("fact");
        if (factRow == null) {
            return new Hit(id, type, archived ? Tier.HISTORY : Tier.CURRENT, archived, text, score);
        }
        Fact fact = fact(factRow);
        String snippet =
                FactLine.snippet(
                        new FactView(fact, (String) row.get("target"), null),
                        strings(row.get("kept")));
        boolean history = archived || fact.validTo() != null;
        return new Hit(id, type, history ? Tier.HISTORY : Tier.CURRENT, archived, snippet, score);
    }

    static List<String> strings(Object value) {
        return value == null
                ? List.of()
                : ((List<?>) value).stream().map(String.class::cast).toList();
    }

    private static Fact fact(Map<?, ?> row) {
        return new Fact(
                (String) row.get("id"),
                (String) row.get("key"),
                (String) row.get("value"),
                (String) row.get("env"),
                Source.valueOf(upper(row.get("source"))),
                Instant.parse((String) row.get("valid_from")),
                Instant.parse((String) row.get("observed_at")),
                instant(row.get("valid_to")),
                FactStatus.valueOf(upper(row.get("status"))),
                null,
                null);
    }

    /**
     * {@code ornek-proje:src/main/resources/application-prod.yml:2} for a source file (the repo's
     * last path segment, SPEC §6.6), the id for a document. Only a source file has a repo; a
     * document has a path too (its blob).
     */
    private static @Nullable String origin(Map<?, ?> row) {
        String repo = (String) row.get("originRepo");
        if (repo == null) {
            return (String) row.get("originId");
        }
        String origin = repo.substring(repo.lastIndexOf('/') + 1) + ":" + row.get("originPath");
        Object line = row.get("originLine");
        return line == null ? origin : origin + ":" + line;
    }

    private static @Nullable Instant instant(Object value) {
        return value == null ? null : Instant.parse((String) value);
    }

    // Enum names are ASCII; the graph holds them in lower case (seed/snapshot).
    private static String upper(Object value) {
        return ((String) value).toUpperCase(Locale.ROOT);
    }
}
