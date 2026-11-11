package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.ScannedFile;
import io.github.ismetdyn.agentkb.server.write.ScanWrites.Outcome;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.Checked;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.CheckedFact;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Node;

/**
 * One file of a scan, inside its one transaction: the facts of each entity, then the project's
 * facts and relations, each as a write request with source scan through RequestWrite (ARCHITECTURE
 * §5); FROM {line, commit} to the file for every scan fact; then closedByRescan per entity.
 */
final class ScanFileWrite {

    private static final String PROJECT_TYPE = "project";
    private static final String FINDING = "finding";

    private final ScanWrites scan;
    private final GraphWrite graph;
    private final ScannedFile file;
    private final Node source;
    // The writer lock keeps every other write out and this file writes no Secret, so the values
    // stored on Secrets are read once per file, not per fact (CP6 profile).
    private final SecretMask stored;
    private final Outcome outcome = new Outcome();

    ScanFileWrite(ScanWrites scan, GraphWrite graph, ScannedFile file) {
        this.scan = scan;
        this.graph = graph;
        this.file = file;
        this.source =
                ScanGraph.sourceFile(
                        graph,
                        graph.tx.findNode(ScanGraph.REPO, GraphWrite.ID, scan.scan.repo()),
                        file.path());
        this.stored = SecretMask.in(graph.tx);
    }

    Outcome apply() {
        Map<String, List<Found>> byEntity = new LinkedHashMap<>();
        List<Found> projectFacts = new ArrayList<>();
        for (Found found : file.facts()) {
            if (found.entity() == null) {
                projectFacts.add(found);
            } else {
                byEntity.computeIfAbsent(found.entity(), hint -> new ArrayList<>()).add(found);
            }
        }
        // Entity hint → the id it was written under, so the project's relations can point there.
        Map<String, String> written = new LinkedHashMap<>();
        byEntity.forEach(
                (hint, facts) -> {
                    String id = write(hint, facts.getFirst().type(), facts, List.of());
                    if (id != null) {
                        written.put(hint, id);
                        scan.raiseDisputes(graph, file.path(), hint, id);
                    }
                });
        List<RelationInput> relations =
                file.links().stream()
                        .filter(
                                link ->
                                        written.containsKey(link.target())
                                                && scan.schema
                                                        .relationType(link.type())
                                                        .isPresent())
                        .map(
                                link ->
                                        new RelationInput(
                                                link.type(),
                                                written.get(link.target()),
                                                scan.schema.environment(link.env())))
                        .distinct()
                        .toList();
        write(null, PROJECT_TYPE, projectFacts, relations);
        scan.raiseDisputes(graph, file.path(), null, scan.projectId);
        return outcome;
    }

    /**
     * The facts of one entity, and for the project its relations, in parts of at most ITEM_LIMIT
     * (ADR-0022 decision 31); closedByRescan runs once, after all of them. A value another file of
     * the scan found for the entity {@code hint} (null: the project) does not close here.
     *
     * @return the id of the entity written, null if nothing was
     */
    private @Nullable String write(
            @Nullable String hint,
            @Nullable String type,
            List<Found> facts,
            List<RelationInput> relations) {
        String entity = hint == null ? scan.projectId : hint;
        List<Found> kept = kept(entity, type, facts);
        // The project still runs closedByRescan: a finding the file no longer backs closes.
        if (kept.isEmpty() && relations.isEmpty() && !entity.equals(scan.projectId)) {
            return null;
        }
        String id = resolve(entity, kept);
        List<CheckedFact> scanned = new ArrayList<>();
        int size = Math.max(kept.size(), relations.size());
        for (int start = 0; start < size; start += WriteCheck.ITEM_LIMIT) {
            id = writePart(id, type, part(kept, start), part(relations, start), scanned);
        }
        Node node = graph.tx.findNode(ScanGraph.ENTITY, GraphWrite.ID, id);
        // The write path refuses every fact under an unknown type, so then none backs a value;
        // a kept fact shows the type passed (ScanWrites checks the other files without it).
        boolean typeKnown = !kept.isEmpty() || isKnownType(type);
        outcome.closed +=
                ScanGraph.closeByRescan(
                                graph,
                                node,
                                source,
                                scanned,
                                fact ->
                                        typeKnown
                                                && scan.backing()
                                                        .isElsewhere(hint, fact, file.path()),
                                scan.at)
                        .size();
        return id;
    }

    private boolean isKnownType(@Nullable String type) {
        try {
            if (!WriteCheck.isBlank(type)) {
                WriteCheck.label(scan.schema, type);
            }
            return true;
        } catch (WriteRejectedException e) {
            return false;
        }
    }

    /** The hint, or the entity holding a hostname or ip the file found for it. */
    private String resolve(String hint, List<Found> facts) {
        return RequestWrite.scanned(
                graph,
                hint,
                facts.stream()
                        .filter(found -> RequestWrite.ADDRESS_KEYS.contains(found.key()))
                        .map(Found::value)
                        .distinct()
                        .toList());
    }

    /**
     * A fact the write path would refuse (key, validator, secret-looking or stored value) is
     * skipped alone and reported without its value; the rest of the file is written. A finding is
     * the scanner's own text of file path and line, no value of the file, so a short stored value
     * that happens to be part of it (a weak password such as {@code yml}) does not refuse it.
     */
    private List<Found> kept(String entity, @Nullable String type, List<Found> facts) {
        List<Found> kept = new ArrayList<>();
        for (Found found : facts) {
            try {
                WriteCheck.check(
                        request(entity, type, List.of(input(found)), List.of()), scan.schema);
                if (!FINDING.equals(found.key())) {
                    SecretWrite.refuseStoredValues(stored, List.of(found.value()));
                }
                kept.add(found);
            } catch (WriteRejectedException e) {
                outcome.skipped.add(
                        file.path()
                                + ":"
                                + found.line()
                                + ": "
                                + found.key()
                                + ": "
                                + e.getMessage());
            }
        }
        return kept;
    }

    /** One request; FROM for its scan facts and their findings. Returns the entity id. */
    private String writePart(
            String entity,
            @Nullable String type,
            List<Found> facts,
            List<RelationInput> relations,
            List<CheckedFact> scanned) {
        WriteRequest request =
                request(entity, type, facts.stream().map(this::input).toList(), relations);
        Checked checked = WriteCheck.check(request, scan.schema);
        RequestWrite write = new RequestWrite(graph, request, checked, scan.at);
        WriteResult result = write.apply();
        if (result.created()) {
            outcome.newEntities.add(result.entity());
        }
        outcome.added += result.added().size();
        outcome.refreshed += result.refreshed().size();
        outcome.closed += result.closed().size();
        outcome.conflicts += result.conflicts().size();
        for (int i = 0; i < checked.facts().size(); i++) {
            Node fact = write.written.get(i);
            // A refreshed fact of a higher source keeps its own provenance (SPEC §7 rule 1).
            if ("scan".equals(fact.getProperty("source", null))) {
                ScanGraph.from(graph, fact, source, facts.get(i).line(), scan.scan.commit());
            }
            CheckedFact checkedFact = checked.facts().get(i);
            if (FINDING.equals(checkedFact.key())) {
                outcome.findings.add(checkedFact.value());
            }
            scanned.add(checkedFact);
        }
        return result.entity();
    }

    private WriteRequest request(
            String entity,
            @Nullable String type,
            List<FactInput> facts,
            List<RelationInput> relations) {
        return new WriteRequest(
                Source.SCAN, entity, type, facts, relations, null, scan.batch, scan.at);
    }

    private FactInput input(Found found) {
        return ScanBacking.input(scan.schema, found);
    }

    private static <T> List<T> part(List<T> items, int start) {
        int from = Math.min(start, items.size());
        return items.subList(from, Math.min(items.size(), from + WriteCheck.ITEM_LIMIT));
    }
}
