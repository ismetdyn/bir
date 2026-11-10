package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import java.util.Map;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;

/**
 * The Document a write's knowledge came from (docs/MCP-TOOLS.md kb_remember {@code
 * source.document}): the facts the write stores get a FROM edge to it, in the write's batch, so kb
 * revert takes it back.
 */
final class DocumentSource {

    private static final Label DOCUMENT = Label.label("Document");
    private static final RelationshipType FROM = RelationshipType.withName("FROM");

    private final GraphWrite graph;
    private final Node document;

    /**
     * @throws WriteRejectedException if {@code id} names no Document; the write is rolled back
     */
    DocumentSource(GraphWrite graph, String id) {
        this.graph = graph;
        Node found = graph.tx.findNode(DOCUMENT, GraphWrite.ID, id.strip());
        if (found == null) {
            throw new WriteRejectedException(
                    "document names no document",
                    "Give the id (doc:...) of an existing document, or leave document out;"
                            + " kb doc list shows them.");
        }
        this.document = found;
    }

    /**
     * FROM {@code fact} to the document, once. A refreshed fact of another (higher) source keeps
     * its own provenance, as a scan's does (SPEC §7 rule 1).
     */
    void link(Node fact, CheckedFact input) {
        if (!Ids.normalize(input.source().name()).equals(fact.getProperty("source", null))) {
            return;
        }
        try (ResourceIterable<Relationship> edges =
                fact.getRelationships(Direction.OUTGOING, FROM)) {
            for (Relationship edge : edges) {
                if (edge.getEndNode().equals(document)) {
                    return;
                }
            }
        }
        graph.edge(fact, FROM.name(), document, Map.of());
    }
}
