package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.Checked;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedRelation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Result;

/**
 * ARCHITECTURE §5 steps 2-5 for one checked request, inside its one transaction: a rejection thrown
 * here rolls back everything before it.
 */
final class RequestWrite {

    private static final Label ENTITY = Label.label("Entity");
    private static final String OBSERVED_AT = "observed_at";
    // ARCHITECTURE §5 step 2: hostname is looked at before ip.
    static final List<String> ADDRESS_KEYS = List.of("hostname", "ip");
    private static final String BY_ADDRESS =
            """
            MATCH (e:Entity)-[:HAS_FACT]->(f:Fact {key: $key})
            WHERE f.value IN $values AND f.valid_to IS NULL AND f.status <> 'conflict'
            RETURN DISTINCT e.id AS id ORDER BY id
            """;
    private static final String MISSING_HINT =
            "Find the record's id with kb_search; a new entity is created by its own kb_remember"
                    + " call with a type.";

    private final GraphWrite graph;
    private final WriteRequest request;
    private final Checked checked;
    private final Instant at;
    // After apply: the fact node holding each checked fact's value, in the order of the request.
    final List<Node> written = new ArrayList<>();

    RequestWrite(GraphWrite graph, WriteRequest request, Checked checked, Instant at) {
        this.graph = graph;
        this.request = request;
        this.checked = checked;
        this.at = at;
    }

    WriteResult apply() {
        Node existing = resolve(request.entity()).or(this::byAddress).orElse(null);
        Node entity = existing == null ? create() : existing;
        FactWrites facts = new FactWrites(graph);
        for (CheckedFact input : checked.facts()) {
            Node target = input.target() == null ? null : target(input.target());
            written.add(facts.write(entity, input, incoming(input), target));
        }
        for (CheckedRelation relation : checked.relations()) {
            relate(entity, relation);
        }
        return new WriteResult(
                (String) entity.getProperty(GraphWrite.ID),
                existing == null,
                graph.batch,
                facts.added,
                facts.refreshed,
                facts.closed,
                facts.conflicts);
    }

    private Optional<Node> resolve(String idOrAlias) {
        return KnowledgeQueries.resolve(graph.tx, idOrAlias)
                .map(id -> graph.tx.findNode(ENTITY, GraphWrite.ID, id));
    }

    /**
     * Step 2 after id and alias: the entity whose open hostname, else ip, fact holds the hint. A
     * waiting conflict value does not count. More than one holder is ambiguous and rejected.
     */
    private Optional<Node> byAddress() {
        String raw = request.entity().strip();
        return byAddress(graph, List.of(raw, Ids.normalize(raw)).stream().distinct().toList());
    }

    /**
     * Step 2 for a host the scanner found: {@code hint} when it is an id or alias, else the id of
     * the entity holding one of {@code addresses} (the hint of a host in an internal domain is only
     * its first label, which no hostname fact holds), else {@code hint}, which then creates it.
     *
     * @throws WriteRejectedException if more than one entity holds them
     */
    static String scanned(GraphWrite graph, String hint, List<String> addresses) {
        if (addresses.isEmpty() || KnowledgeQueries.resolve(graph.tx, hint).isPresent()) {
            return hint;
        }
        return byAddress(graph, addresses)
                .map(node -> (String) node.getProperty(GraphWrite.ID))
                .orElse(hint);
    }

    /**
     * The entity whose open hostname, else ip, fact holds one of {@code values}.
     *
     * @throws WriteRejectedException if more than one entity holds them
     */
    private static Optional<Node> byAddress(GraphWrite graph, List<String> values) {
        for (String key : ADDRESS_KEYS) {
            List<String> ids;
            try (Result rows = graph.tx.execute(BY_ADDRESS, Map.of("key", key, "values", values))) {
                ids = rows.stream().map(row -> (String) row.get("id")).toList();
            }
            if (ids.size() > 1) {
                throw new WriteRejectedException(
                        "more than one entity has this " + key + ": " + String.join(", ", ids),
                        "Pass the id of the entity you mean: one of "
                                + String.join(", ", ids)
                                + ".");
            }
            if (ids.size() == 1) {
                return Optional.of(graph.tx.findNode(ENTITY, GraphWrite.ID, ids.getFirst()));
            }
        }
        return Optional.empty();
    }

    private Node target(String idOrAlias) {
        return resolve(idOrAlias)
                .orElseThrow(
                        () -> new WriteRejectedException("no entity with this id", MISSING_HINT));
    }

    /** Step 2: scan creates observed, every other source current (SPEC §7). */
    private Node create() {
        if (checked.label() == null) {
            throw new WriteRejectedException(
                    "no entity with this id or alias",
                    "Pass type (an entity type listed by kb_schema) to create it, or find the"
                            + " existing id with kb_search.");
        }
        String id = Ids.normalize(request.entity().strip());
        // Neo4j Community has no id constraint across labels (lead decision, todo G7).
        for (String label : Schema.LABELS) {
            if (graph.tx.findNode(Label.label(label), GraphWrite.ID, id) != null) {
                throw new WriteRejectedException(
                        "another record that is not an entity has this id",
                        "Choose another id, or find the entity you mean with kb_search.");
            }
        }
        String status = request.source() == Source.SCAN ? "observed" : "current";
        return graph.node(id, Map.of("status", status), ENTITY.name(), checked.label());
    }

    /** WritePolicy sets the status; observed_at and valid_from are the time of the write. */
    private Fact incoming(CheckedFact input) {
        return new Fact(
                Ulid.next(),
                input.key(),
                input.value(),
                input.env(),
                input.source(),
                at,
                at,
                null,
                FactStatus.OK,
                request.evidence() == null ? null : request.evidence().strip(),
                graph.batch);
    }

    /**
     * An open relation of the same type, target and env is seen again: only observed_at moves
     * (decision 10). A relation has no value, so nothing else ever closes it here; kb revert can
     * reopen only what SUPERSEDES points to, which an edge cannot have.
     */
    private void relate(Node entity, CheckedRelation input) {
        Node target = target(input.target());
        RelationshipType type = RelationshipType.withName(input.type());
        String seen = Times.format(at);
        try (ResourceIterable<Relationship> edges =
                entity.getRelationships(Direction.OUTGOING, type)) {
            for (Relationship edge : edges) {
                if (edge.getEndNode().equals(target)
                        && !edge.hasProperty("valid_to")
                        && Objects.equals(input.env(), edge.getProperty("env", null))) {
                    if (seen.compareTo((String) edge.getProperty(OBSERVED_AT, "")) > 0) {
                        graph.set(edge, OBSERVED_AT, seen);
                    }
                    return;
                }
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        if (input.env() != null) {
            props.put("env", input.env());
        }
        props.put("source", Ids.normalize(input.source().name()));
        props.put("valid_from", seen);
        props.put(OBSERVED_AT, seen);
        graph.edge(entity, input.type(), target, props);
    }
}
