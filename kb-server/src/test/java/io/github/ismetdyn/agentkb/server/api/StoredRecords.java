package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.Result;

/** What the REST writes left in the graph, read past the API, for the H6 endpoint tests. */
final class StoredRecords {

    private StoredRecords() {}

    static String id() {
        return "zz-h6-entity-" + UUID.randomUUID();
    }

    /** A new user server with one ip fact; its id. */
    static String server(WriteService writes) {
        String id = id();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        id,
                        "server",
                        List.of(new FactInput("ip", "192.0.2.99", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));
        return id;
    }

    /** The one node or relation {@code cypher} returns for {@code id}, as its properties. */
    static Map<String, Object> node(EmbeddedNeo4jStore store, String cypher, String id) {
        List<Map<String, Object>> found = rows(store, cypher, Map.of("id", id));
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** Each row's one column as the properties of its node or relation. */
    static List<Map<String, Object>> rows(
            EmbeddedNeo4jStore store, String cypher, Map<String, Object> params) {
        return store.read(
                tx -> {
                    List<Map<String, Object>> found = new ArrayList<>();
                    try (Result result = tx.execute(cypher, params)) {
                        result.forEachRemaining(
                                row ->
                                        found.add(
                                                ((Entity) row.values().iterator().next())
                                                        .getAllProperties()));
                    }
                    return found;
                });
    }
}
