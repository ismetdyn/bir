package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

/**
 * {@code POST /proposals/{id}/accept|reject} (ADR-0025 decision 12, SPEC §7 rule 6): both set the
 * status of an open proposal of any kind, after a Revision kept the old one (decision 15). Only an
 * accept acts: archive archives its target, decision opens a Decision (rule = first line, body =
 * content, APPLIES_TO a Stack or Project target, ABOUT an entity target), schema writes the key
 * into schema/facts.yaml (SchemaAccept); rule-change only changes the status, the user edits the
 * rule text.
 */
final class ProposalReview {

    private static final Label PROPOSAL = Label.label("Proposal");
    private static final Label ENTITY = Label.label("Entity");
    private static final Label PROJECT = Label.label("Project");
    private static final Label STACK = Label.label("Stack");
    private static final RelationshipType ABOUT = RelationshipType.withName("ABOUT");
    private static final String OPEN = "open";
    private static final String CONTENT = "content";
    private static final String ACCEPTED = "accepted";
    private static final String REVIEW_NOTE = "review_note";
    // ADR-0025 decision 18: a note is limited like a summary.
    private static final int NOTE_LIMIT = WriteCheck.SUMMARY_LIMIT;

    private ProposalReview() {}

    static void accept(GraphWrite graph, RepoSchema schema, String id) {
        Node proposal = open(graph, id);
        Node target = target(proposal);
        String kind = (String) proposal.getProperty("kind", "");
        switch (kind) {
            case "archive" -> EntityEdits.setArchived(graph, archiveTarget(target), true);
            case "decision" -> decision(graph, (String) proposal.getProperty(CONTENT), target);
            default -> {
                // rule-change: the user edits the rule text (decision 12); schema: below.
            }
        }
        Revisions.keep(graph, proposal, EntityEdits.STATUS);
        graph.set(proposal, EntityEdits.STATUS, ACCEPTED);
        if ("schema".equals(kind)) {
            // Last: a refused edit before it leaves the file as it was.
            SchemaAccept.add(schema, (String) proposal.getProperty(CONTENT));
        }
    }

    /**
     * The note goes into the snapshot and git, so it is checked like a summary (ADR-0025 decision
     * 18).
     */
    static void reject(GraphWrite graph, String id, @Nullable String note) {
        String text = note == null ? null : WriteCheck.text("note", note, NOTE_LIMIT);
        SecretWrite.refuseStoredValues(graph.tx, Collections.singletonList(text));
        Node proposal = open(graph, id);
        if (text == null) {
            Revisions.keep(graph, proposal, EntityEdits.STATUS);
        } else {
            Revisions.keep(graph, proposal, EntityEdits.STATUS, REVIEW_NOTE);
            graph.set(proposal, REVIEW_NOTE, text);
        }
        graph.set(proposal, EntityEdits.STATUS, "rejected");
    }

    private static Node open(GraphWrite graph, @Nullable String id) {
        Node proposal =
                WriteCheck.isBlank(id) ? null : graph.tx.findNode(PROPOSAL, GraphWrite.ID, id);
        if (proposal == null || !OPEN.equals(proposal.getProperty(EntityEdits.STATUS, null))) {
            throw new WriteRejectedException(
                    "no open proposal with this id",
                    "Only an open proposal is accepted or rejected; list them with kb proposals.");
        }
        return proposal;
    }

    private static @Nullable Node target(Node proposal) {
        Relationship about = proposal.getSingleRelationship(ABOUT, Direction.OUTGOING);
        return about == null ? null : about.getEndNode();
    }

    private static Node archiveTarget(@Nullable Node target) {
        if (target == null || !target.hasLabel(ENTITY)) {
            throw new WriteRejectedException(
                    "the archive proposal names no entity",
                    "Reject it; an archive proposal names the entity to archive as its target.");
        }
        return target;
    }

    /**
     * SPEC §6.4: scope project when it applies to a project, otherwise global. SPEC §6.2: a
     * Decision APPLIES_TO a Stack or Project and is ABOUT another entity; any other target is
     * refused.
     */
    private static void decision(GraphWrite graph, String content, @Nullable Node target) {
        boolean appliesTo = target != null && (target.hasLabel(STACK) || target.hasLabel(PROJECT));
        if (target != null && !appliesTo && !target.hasLabel(ENTITY)) {
            throw new WriteRejectedException(
                    "the decision proposal names a record that is no entity",
                    "Reject it; a decision applies to a stack or project or is about an entity.");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rule", content.lines().findFirst().orElse(content).strip());
        props.put("body", content);
        props.put("scope", target != null && target.hasLabel(PROJECT) ? "project" : "global");
        props.put("date", LocalDate.now(ZoneOffset.UTC).toString());
        props.put(EntityEdits.STATUS, ACCEPTED);
        Node decision = graph.node(Ulid.next(), props, "Decision");
        if (target != null) {
            graph.edge(decision, appliesTo ? "APPLIES_TO" : ABOUT.name(), target, Map.of());
        }
    }
}
