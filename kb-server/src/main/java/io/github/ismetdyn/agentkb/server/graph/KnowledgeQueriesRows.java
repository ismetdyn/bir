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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
    // yyyy-MM-dd of an ISO-8601 time (Times.format).
    private static final int DATE_CHARS = 10;

    private KnowledgeQueriesRows() {}

    static FactView factView(Map<?, ?> row) {
        return new FactView(
                fact((Map<?, ?>) row.get("fact")),
                (String) row.get("target"),
                origin(row),
                episode(row));
    }

    /**
     * ADR-0023 decision 5: {@code episode: codex, 2026-11-06} for the Episode a fact came from,
     * with the transcript path when that file is on this machine; null without an Episode.
     */
    private static @Nullable String episode(Map<?, ?> row) {
        if (!(row.get("episodeClient") instanceof String client)) {
            return null;
        }
        StringBuilder episode = new StringBuilder("episode: ").append(client);
        // Imported rows are taken as they are (invariant 11), so the date is cut, not parsed.
        if (row.get("episodeAt") instanceof String at && at.length() >= DATE_CHARS) {
            episode.append(", ").append(at, 0, DATE_CHARS);
        }
        if (row.get("episodeTranscript") instanceof String transcript && isLocal(transcript)) {
            episode.append(", ").append(transcript);
        }
        return episode.toString();
    }

    // ponytail: a stat per read fact with a transcript; a path that came with a snapshot from
    // another machine is simply not there. Cache it if kb_read of large records gets slow.
    private static boolean isLocal(String transcript) {
        try {
            return Files.isRegularFile(Path.of(transcript));
        } catch (InvalidPathException e) {
            return false;
        }
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
                instant(row.get("validTo")),
                // A path step (RELATED) has no id; the RELATIONS of one entity have.
                (String) row.get("id"));
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
        // The masked id; the raw one (column id) only keys search's best hit per record.
        String id = (String) row.get("record");
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
