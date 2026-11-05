package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * The writes that know {@code secret_value} (docs/SECRETS.md §2, invariant 2): setting it through
 * {@code PUT /api/v1/secrets/<id>}, and refusing a new write that would copy a stored value into
 * the graph where MCP, the snapshot and git can see it. Messages never carry a value.
 */
final class SecretWrite {

    static final String SECRET_VALUE = "secret_value";
    private static final Label SECRET = Label.label("Secret");
    private static final Label ENTITY = Label.label("Entity");
    private static final String HAS_SECRET = "HAS_SECRET";
    private static final String SCAN = "scan";
    private static final String USER = "user";
    private static final String SOURCE = "source";
    private static final String OBSERVED_AT = "observed_at";
    private static final String STORED_VALUES =
            "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN s.secret_value AS v";
    private static final int ID_PARTS = 3;
    private static final String ID_HINT =
            "A secret id is <entity>/<env>/<name>, e.g. ornek-db/prod/app-password; the entity"
                    + " must exist.";
    private static final String STORED_HINT =
            "This value is stored as a secret; refer to it as kb:secret/<id> (kb_read shows the"
                    + " id).";

    private SecretWrite() {}

    /**
     * Sets the value of the Secret {@code id}, creating it under its entity when it is new. The old
     * value is overwritten, not kept (SPEC §7 rule 9). The Secret becomes user-owned: source user
     * and the batch of this write, so reverting the scan that opened it keeps it and a rescan never
     * overwrites the value. The value itself never leaves this machine, so it does not move
     * updated_at; the changed source does.
     */
    static @Nullable Void put(GraphWrite graph, String id, String value) {
        Node secret = graph.tx.findNode(SECRET, GraphWrite.ID, id);
        if (secret == null) {
            secret = create(graph, id);
        } else {
            if (!USER.equals(secret.getProperty(SOURCE, null))) {
                graph.set(secret, SOURCE, USER);
            }
            secret.setProperty(GraphWrite.BATCH, graph.batch);
        }
        secret.setProperty(SECRET_VALUE, value);
        return null;
    }

    /**
     * A secret the scanner read from a file (docs/SECRETS.md), {@code id} as {@code
     * <owner>/<env>/<name>}: new ones open under {@code owner} with source scan. A scanned or still
     * empty secret takes the value of the file; a value the user set stays, and a different one in
     * the file raises a conflict signal that names the id only (SPEC §7 rule 9). Like {@link #put},
     * a new value does not move updated_at.
     */
    static Node scanned(GraphWrite graph, Node owner, String id, String value, String seen) {
        Node secret = graph.tx.findNode(SECRET, GraphWrite.ID, id);
        if (secret == null) {
            String[] parts = id.split("/", -1);
            secret =
                    graph.node(
                            id,
                            Map.of(
                                    "name",
                                    parts[ID_PARTS - 1],
                                    "env",
                                    parts[1],
                                    SOURCE,
                                    SCAN,
                                    OBSERVED_AT,
                                    seen),
                            SECRET.name());
            graph.edge(owner, HAS_SECRET, secret, Map.of());
        } else if (seen.compareTo((String) secret.getProperty(OBSERVED_AT, "")) > 0) {
            graph.set(secret, OBSERVED_AT, seen);
        }
        Object stored = secret.getProperty(SECRET_VALUE, null);
        if (value.equals(stored)) {
            return secret;
        }
        if (stored == null || SCAN.equals(secret.getProperty(SOURCE, null))) {
            secret.setProperty(SECRET_VALUE, value);
        } else {
            conflict(
                    graph,
                    owner,
                    id,
                    "a scanned file holds another value; the value the user set is kept",
                    seen);
        }
        return secret;
    }

    /**
     * The same scan found a second, different value for the secret {@code id} that {@link #scanned}
     * already wrote: the value stays as it is and a conflict signal names the id only, so the user
     * learns that two settings share one name (SPEC §7 rule 9).
     */
    static Node scannedTwice(GraphWrite graph, Node owner, String id, String seen) {
        conflict(
                graph,
                owner,
                id,
                "two scanned settings with this name hold different values; the first is kept",
                seen);
        return graph.tx.findNode(SECRET, GraphWrite.ID, id);
    }

    private static void conflict(
            GraphWrite graph, Node owner, String id, String reason, String seen) {
        Node signal =
                SignalWrite.raise(graph, "conflict", id, "secret " + id + ": " + reason, seen);
        if (signal != null) {
            graph.localEdge(signal, "ABOUT", owner);
        }
    }

    private static Node create(GraphWrite graph, String id) {
        String[] parts = id.split("/", -1);
        if (parts.length != ID_PARTS) {
            throw new WriteRejectedException("the secret id is not <entity>/<env>/<name>", ID_HINT);
        }
        for (String part : parts) {
            if (!part.equals(WriteCheck.text("secret id part", part, WriteCheck.ID_LIMIT))) {
                throw new WriteRejectedException("a secret id part has outer spaces", ID_HINT);
            }
        }
        Node entity = graph.tx.findNode(ENTITY, GraphWrite.ID, parts[0]);
        if (entity == null) {
            throw new WriteRejectedException("no entity with this id", ID_HINT);
        }
        Node secret =
                graph.node(
                        id,
                        Map.of(
                                "name",
                                parts[2],
                                "env",
                                parts[1],
                                SOURCE,
                                USER,
                                "observed_at",
                                Times.format(Instant.now())),
                        SECRET.name());
        graph.edge(entity, HAS_SECRET, secret, Map.of());
        return secret;
    }

    /** Refuses the write when any of {@code texts} holds a value stored on a Secret. */
    static void refuseStoredValues(Transaction tx, List<@Nullable String> texts) {
        List<String> stored = storedValues(tx);
        for (String text : texts) {
            if (text != null && stored.stream().anyMatch(text::contains)) {
                throw new WriteRejectedException("a value is a stored secret value", STORED_HINT);
            }
        }
    }

    /**
     * {@code text} with every value stored on a Secret replaced by {@code mask}; the longest value
     * first, so no part of a longer value is left behind a shorter one inside it.
     */
    static String maskStoredValues(Transaction tx, String text, String mask) {
        String masked = text;
        for (String value :
                storedValues(tx).stream()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList()) {
            masked = masked.replace(value, mask);
        }
        return masked;
    }

    private static List<String> storedValues(Transaction tx) {
        try (Result result = tx.execute(STORED_VALUES)) {
            return result.stream()
                    .map(row -> String.valueOf(row.get("v")))
                    .filter(value -> !value.isEmpty())
                    .toList();
        }
    }

    /** Every free text of a write request: what could end up in a record. */
    static List<@Nullable String> texts(WriteRequest request) {
        List<@Nullable String> texts = new ArrayList<>();
        texts.add(request.entity());
        texts.add(request.type());
        texts.add(request.evidence());
        request.facts()
                .forEach(
                        fact -> {
                            texts.add(fact.value());
                            texts.add(fact.target());
                        });
        request.relations().forEach(relation -> texts.add(relation.target()));
        return texts;
    }
}
