package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

/**
 * SPEC §7 rule 6: an agent never writes a normative change; it opens a Proposal, ABOUT the record
 * it would change, and nothing else moves until the user accepts it (ADR-0022 decision 15). The
 * text goes into the snapshot and git, so it is checked like a fact.
 */
final class ProposalWrite {

    static final List<String> KINDS = List.of("decision", "rule-change", "archive", "schema");
    // Signals are local; a proposal in the snapshot must not point to one (invariant 8). A Revision
    // is the bookkeeping of an edit (ADR-0025 decision 15), nothing a proposal changes.
    private static final List<String> TARGET_LABELS =
            Schema.LABELS.stream()
                    .filter(label -> !"Signal".equals(label) && !Revisions.LABEL.equals(label))
                    .toList();
    private static final String PROPOSAL = "Proposal";

    private ProposalWrite() {}

    static void check(ProposalRequest request) {
        if (request.kind() == null || !KINDS.contains(request.kind().strip())) {
            throw new WriteRejectedException(
                    "unknown proposal kind", "Use one of kind: " + String.join(", ", KINDS) + ".");
        }
        WriteCheck.text("content", request.content(), WriteCheck.BODY_LIMIT);
        if (request.evidence() != null) {
            WriteCheck.text("evidence", request.evidence(), WriteCheck.BODY_LIMIT);
        }
        if (request.target() != null) {
            WriteCheck.text("target", request.target(), WriteCheck.ID_LIMIT);
        }
    }

    /**
     * Status open until the user accepts or rejects it in the TUI or GUI (open, accepted or
     * rejected; ADR-0022 decision 31).
     */
    static String write(GraphWrite graph, ProposalRequest request) {
        Node target = request.target() == null ? null : target(graph, request.target());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", request.kind().strip());
        props.put("content", request.content().strip());
        if (request.evidence() != null) {
            props.put("evidence", request.evidence().strip());
        }
        props.put("by", Ids.normalize(request.by().name()));
        props.put("status", "open");
        String id = Ulid.next();
        Node proposal = graph.node(id, props, PROPOSAL);
        if (target != null) {
            graph.edge(proposal, "ABOUT", target, Map.of());
        }
        return id;
    }

    /** An entity by id or alias, else any record but a signal by its id. */
    private static Node target(GraphWrite graph, String idOrAlias) {
        Optional<String> entity = KnowledgeQueries.resolve(graph.tx, idOrAlias);
        if (entity.isPresent()) {
            return graph.tx.findNode(Label.label("Entity"), GraphWrite.ID, entity.get());
        }
        String id = idOrAlias.strip();
        return TARGET_LABELS.stream()
                .map(label -> graph.tx.findNode(Label.label(label), GraphWrite.ID, id))
                .filter(node -> node != null)
                .findFirst()
                .orElseThrow(
                        () ->
                                new WriteRejectedException(
                                        "no record with this target id",
                                        "Find the record's id with kb_search or kb_read."));
    }
}
