package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * ARCHITECTURE §4: an id constraint per label and per relationship type, and the fulltext index.
 * Neo4j Community has no constraint across labels or types (Neo4jCommunityBehaviourTest); {@code
 * WriteService} checks across them. Every step is idempotent.
 */
final class GraphSchema {

    // SPEC §6.1. A type label (Server, ...) always sits next to Entity, whose constraint covers it.
    private static final List<String> LABELS = Schema.LABELS;
    // SPEC §6.2 fixed edges. Vocabulary types (schema/relations.yaml) are data, not code: they get
    // a constraint from the rows that carry them.
    private static final List<String> EDGE_TYPES = Schema.FIXED_EDGE_TYPES;

    private static final String FULLTEXT_NAME = "kb_fulltext";
    // ARCHITECTURE §4 fields; the analyzer is ADR-0018 decision 6.
    private static final List<String> FULLTEXT_LABELS =
            List.of("Entity", "Fact", "Decision", "Lesson", "Document", "Signal");
    private static final List<String> FULLTEXT_PROPERTIES =
            List.of(
                    "id", "aliases", "summary", "value", "rule", "body", "title", "name",
                    "payload");
    private static final String FULLTEXT_ANALYZER = "standard-folding";
    private static final String ANALYZER_KEY = "fulltext.analyzer";
    // Populating the index of the measured 10k-record graph takes seconds; longer means broken.
    private static final long ONLINE_TIMEOUT_SECONDS = 120;

    private GraphSchema() {}

    /**
     * @param labels labels beyond SPEC §6.1 that rows to be written carry (import is lossless)
     * @param types relationship types beyond the fixed edges that rows to be written carry
     */
    static void ensure(
            EmbeddedNeo4jStore store, Collection<String> labels, Collection<String> types) {
        // Neo4j refuses schema and data changes in one transaction, so the schema has its own.
        store.write(
                tx -> {
                    union(LABELS, labels).forEach(label -> constrainNodes(tx, label));
                    union(EDGE_TYPES, types).forEach(type -> constrainEdges(tx, type));
                    ensureFulltext(tx);
                    return null;
                });
        // The first search after start must not meet a populating index.
        store.read(
                tx -> {
                    tx.schema().awaitIndexesOnline(ONLINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return null;
                });
    }

    /** {@link #ensure} for the labels and types the rows of {@code snapshot} carry. */
    static void ensureFor(EmbeddedNeo4jStore store, Snapshot snapshot) {
        Set<String> labels = new TreeSet<>();
        snapshot.nodes().forEach(node -> labels.addAll(node.labels()));
        Set<String> types = new TreeSet<>();
        snapshot.edges().forEach(edge -> types.add(edge.type()));
        ensure(store, labels, types);
    }

    private static Set<String> union(List<String> fixed, Collection<String> extra) {
        Set<String> all = new TreeSet<>(fixed);
        all.addAll(extra);
        return all;
    }

    private static void constrainNodes(Transaction tx, String label) {
        tx.execute(
                "CREATE CONSTRAINT IF NOT EXISTS FOR (n:"
                        + quoted(label)
                        + ") REQUIRE n.id IS UNIQUE");
    }

    private static void constrainEdges(Transaction tx, String type) {
        tx.execute(
                "CREATE CONSTRAINT IF NOT EXISTS FOR ()-[r:"
                        + quoted(type)
                        + "]-() REQUIRE r.id IS UNIQUE");
    }

    /**
     * IF NOT EXISTS matches by name only, so a changed definition would never reach an existing
     * graph. The index is derived data: rebuilding it loses nothing.
     */
    private static void ensureFulltext(Transaction tx) {
        Optional<Map<String, Object>> existing = fulltextIndex(tx);
        if (existing.isPresent() && isCurrent(existing.get())) {
            return;
        }
        if (existing.isPresent()) {
            tx.execute("DROP INDEX " + FULLTEXT_NAME);
        }
        tx.execute(
                "CREATE FULLTEXT INDEX "
                        + FULLTEXT_NAME
                        + " FOR (n:"
                        + String.join("|", FULLTEXT_LABELS)
                        + ") ON EACH ["
                        + FULLTEXT_PROPERTIES.stream()
                                .map(property -> "n." + property)
                                .collect(Collectors.joining(", "))
                        + "] OPTIONS {indexConfig: {`"
                        + ANALYZER_KEY
                        + "`: '"
                        + FULLTEXT_ANALYZER
                        + "'}}");
    }

    private static Optional<Map<String, Object>> fulltextIndex(Transaction tx) {
        try (Result result =
                tx.execute(
                        "SHOW FULLTEXT INDEXES YIELD name, labelsOrTypes, properties, options"
                                + " WHERE name = $name",
                        Map.of("name", FULLTEXT_NAME))) {
            return result.stream().findFirst();
        }
    }

    private static boolean isCurrent(Map<String, Object> index) {
        Map<?, ?> config = (Map<?, ?>) ((Map<?, ?>) index.get("options")).get("indexConfig");
        return Set.copyOf((List<?>) index.get("labelsOrTypes")).equals(Set.copyOf(FULLTEXT_LABELS))
                && Set.copyOf((List<?>) index.get("properties"))
                        .equals(Set.copyOf(FULLTEXT_PROPERTIES))
                && FULLTEXT_ANALYZER.equals(config.get(ANALYZER_KEY));
    }

    private static String quoted(String name) {
        return '`' + name.replace("`", "``") + '`';
    }
}
