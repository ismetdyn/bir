package io.github.ismetdyn.agentkb.server.secrets;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.Placeholders;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import io.github.ismetdyn.agentkb.server.write.SecretWrite;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.springframework.stereotype.Service;

/**
 * The one way a secret value leaves the graph (invariant 2, docs/SECRETS.md §5): only the REST
 * endpoint uses this, never MCP (ArchUnit). Writes go through {@link WriteService} (invariant 1).
 */
@Service
public class SecretService {

    private static final Label SECRET = Label.label("Secret");
    private static final String VALUE =
            "MATCH (s:Secret {id: $id}) WHERE s.secret_value IS NOT NULL"
                    + " RETURN s.secret_value AS v";

    private final EmbeddedNeo4jStore store;
    private final WriteService writer;

    public SecretService(EmbeddedNeo4jStore store, WriteService writer) {
        this.store = store;
        this.writer = writer;
    }

    /** Empty both for an unknown id and for a Secret whose value was set on another machine. */
    public Optional<String> value(String id) {
        return store.read(
                tx -> {
                    try (var rows = tx.execute(VALUE, Map.of("id", id))) {
                        return rows.stream().map(row -> (String) row.get("v")).findFirst();
                    }
                });
    }

    /**
     * The values a document scan refuses (ADR-0025 decision 3).
     *
     * @see SecretWrite#storedValues
     */
    public List<String> storedValues() {
        // F4 (ADR-0026): a shorter value is no secret; the document scan keeps plain contains.
        return store.read(SecretWrite::storedValues).stream()
                .filter(value -> value.length() >= Placeholders.MIN_SECRET_CHARS)
                .toList();
    }

    /**
     * Stores the value (WriteService#putSecret).
     *
     * @return how many records already held it as plain text (ADR-0026): written before it was
     *     stored, so possibly in the snapshot and the git history too
     */
    public int set(String id, String value) {
        int holding = store.read(tx -> recordsHolding(tx, value));
        writer.putSecret(id, value);
        return holding;
    }

    // Every record but the Secrets, as a read masks it: as written or normalized (SecretMask).
    private static int recordsHolding(Transaction tx, String value) {
        SecretMask mask = SecretMask.of(List.of(value, Ids.normalize(value)));
        int holding = 0;
        try (ResourceIterable<Node> nodes = tx.getAllNodes()) {
            for (Node node : nodes) {
                if (!node.hasLabel(SECRET) && holds(node, mask)) {
                    holding++;
                }
            }
        }
        return holding;
    }

    private static boolean holds(Node node, SecretMask mask) {
        for (Object property : node.getAllProperties().values()) {
            if (property instanceof String text && mask.holds(text)
                    || property instanceof String[] texts
                            && Arrays.stream(texts).anyMatch(mask::holds)) {
                return true;
            }
        }
        return false;
    }
}
