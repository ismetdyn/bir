package io.github.ismetdyn.agentkb.server.secrets;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.SecretWrite;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The one way a secret value leaves the graph (invariant 2, docs/SECRETS.md §5): only the REST
 * endpoint uses this, never MCP (ArchUnit). Writes go through {@link WriteService} (invariant 1).
 */
@Service
public class SecretService {

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
        return store.read(SecretWrite::storedValues);
    }

    /**
     * @see WriteService#putSecret
     */
    public void set(String id, String value) {
        writer.putSecret(id, value);
    }
}
