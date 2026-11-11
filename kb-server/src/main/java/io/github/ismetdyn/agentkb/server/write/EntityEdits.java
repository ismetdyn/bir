package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;

/**
 * {@code PATCH /entities/{id}} and {@code kb archive}/{@code unarchive} (CLIENTS §1): entity fields
 * change in place (SPEC §6.3), after a Revision kept the old values (ADR-0025 decision 15). Archive
 * is only a status; nothing of the entity is hidden or deleted (invariant 12).
 */
final class EntityEdits {

    static final String STATUS = "status";
    private static final String ALIASES = "aliases";
    private static final String SUMMARY = "summary";
    private static final String ARCHIVED = "archived";
    private static final String CURRENT = "current";
    private static final Label ENTITY = Label.label("Entity");

    private EntityEdits() {}

    /** Aliases are normalized in the one place (Ids.normalize), so they resolve in any case. */
    static void edit(
            GraphWrite graph,
            String idOrAlias,
            @Nullable List<String> aliases,
            @Nullable String summary) {
        if (aliases == null && summary == null) {
            throw new WriteRejectedException(
                    "nothing to change", "Give aliases, a summary or both.");
        }
        Node entity = entity(graph, idOrAlias);
        // The new value of each field the edit sets; texts as the request gave them.
        Map<String, Object> changes = new LinkedHashMap<>();
        List<@Nullable String> texts = new ArrayList<>();
        String[] normalized = new String[0];
        if (aliases != null) {
            List<String> given =
                    aliases.stream()
                            .map(alias -> WriteCheck.text("alias", alias, WriteCheck.ID_LIMIT))
                            .toList();
            texts.addAll(given);
            normalized = given.stream().map(Ids::normalize).distinct().toArray(String[]::new);
            changes.put(ALIASES, normalized);
        }
        if (summary != null) {
            String text = WriteCheck.text(SUMMARY, summary, WriteCheck.SUMMARY_LIMIT);
            texts.add(text);
            changes.put(SUMMARY, text);
        }
        // An alias is kept normalized; the refusal looks at that form too (CP3 S1, ADR-0026).
        SecretWrite.refuseStoredValues(SecretMask.in(graph.tx), texts);
        refuseOtherIds(graph, entity, normalized);
        Revisions.keep(graph, entity, changes.keySet().toArray(String[]::new));
        changes.forEach((field, value) -> graph.set(entity, field, value));
    }

    /** An alias that is another entity's id would make that id name two entities (CP3 J7). */
    private static void refuseOtherIds(GraphWrite graph, Node entity, String[] aliases) {
        for (String alias : aliases) {
            Node other = graph.tx.findNode(ENTITY, GraphWrite.ID, alias);
            if (other != null && !other.equals(entity)) {
                throw new WriteRejectedException(
                        "an alias is the id of another entity",
                        "Choose another alias; kb read <alias> shows the entity with that id.");
            }
        }
    }

    /**
     * Archives or brings back an entity; unarchived it is current (the user confirms it). Archiving
     * an archived entity or bringing back one that is not archived is refused (ADR-0025 decision
     * 18).
     */
    static void setArchived(GraphWrite graph, Node entity, boolean archived) {
        if (archived == ARCHIVED.equals(entity.getProperty(STATUS, null))) {
            throw archived
                    ? new WriteRejectedException(
                            "the entity is already archived",
                            "Nothing to do; kb unarchive <id> brings it back.")
                    : new WriteRejectedException(
                            "the entity is not archived", "Only an archived entity is unarchived.");
        }
        Revisions.keep(graph, entity, STATUS);
        graph.set(entity, STATUS, archived ? ARCHIVED : CURRENT);
    }

    /** An entity by id or alias. */
    static Node entity(GraphWrite graph, @Nullable String idOrAlias) {
        return (WriteCheck.isBlank(idOrAlias)
                        ? Optional.<String>empty()
                        : KnowledgeQueries.resolve(graph.tx, idOrAlias))
                .map(id -> graph.tx.findNode(ENTITY, GraphWrite.ID, id))
                .orElseThrow(
                        () ->
                                new WriteRejectedException(
                                        "no entity with this id or alias",
                                        "Find the entity's id with kb search."));
    }
}
