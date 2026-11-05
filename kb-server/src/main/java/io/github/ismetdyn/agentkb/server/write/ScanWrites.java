package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.FoundSecret;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.ScannedFile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Node;

/**
 * Applies one {@link ScanWrite} (ARCHITECTURE §6, ADR-0022 decision 31). {@link #open} runs first
 * in a transaction of its own: the project, its Repo and every secret of the scan, so a fact of any
 * file that holds a stored value is refused afterwards (invariant 2). Then {@link #file} runs once
 * per file, each in its own transaction ({@link ScanFileWrite}). Each returns an {@link Outcome}
 * that counts only once its transaction committed ({@link #merge}).
 */
final class ScanWrites {

    // The scan with one value per single-valued slot (ScanSlots); files() is what gets written.
    final ScanWrite scan;
    final List<ScanSlots.Dispute> disputes;
    final Schema schema;
    final String batch;
    final Instant at;
    // The id the project resolved to; set by open().
    String projectId;
    private final Outcome total = new Outcome();
    // Built on first use, after open() set projectId; one per scan (CP5 decision 1).
    private @Nullable ScanBacking backing;

    ScanWrites(ScanWrite scan, Schema schema, String batch, Instant at) {
        ScanSlots.Reduced reduced = ScanSlots.reduce(scan, schema);
        this.scan = reduced.scan();
        this.disputes = reduced.disputes();
        this.schema = schema;
        this.batch = batch;
        this.at = at;
        this.projectId = scan.project();
    }

    /** What one transaction wrote; mutable only while it runs. */
    static final class Outcome {
        final List<String> newEntities = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();
        final List<String> findings = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        int added;
        int refreshed;
        int closed;
        int conflicts;
    }

    /** The vocabulary types of every relation of the scan, for WriteService.constrain. */
    Set<String> relationTypes() {
        Set<String> types = new TreeSet<>();
        for (ScannedFile file : scan.files()) {
            file.links().forEach(link -> schema.relationType(link.type()).ifPresent(types::add));
        }
        return types;
    }

    Outcome open(GraphWrite graph) {
        Outcome outcome = new Outcome();
        Node project = ScanGraph.project(graph, schema, scan.project(), outcome.newEntities);
        projectId = (String) project.getProperty(GraphWrite.ID);
        Node repo = ScanGraph.repo(graph, scan.repo(), scan.remote());
        if (!ScanGraph.linked(project, ScanGraph.HAS_REPO, repo)) {
            // Throws before any secret is written; the transaction rolls the Repo back.
            ScanGraph.refuseOtherGroup(project, scan.repo());
            graph.edge(project, ScanGraph.HAS_REPO, repo, Map.of());
        }
        // Secret id → the value this scan gave it first.
        Map<String, String> values = new HashMap<>();
        for (ScannedFile file : scan.files()) {
            for (FoundSecret secret : file.secrets()) {
                String env = schema.environment(secret.env());
                String name = secret.name().strip();
                if (!isIdPart(name) || !isIdPart(env)) {
                    outcome.skipped.add(
                            file.path()
                                    + ":"
                                    + secret.line()
                                    + ": the secret name cannot be part of a secret id");
                    continue;
                }
                String id = projectId + "/" + env + "/" + name;
                String first = values.putIfAbsent(id, secret.value());
                Node node =
                        first == null || first.equals(secret.value())
                                ? SecretWrite.scanned(
                                        graph, project, id, secret.value(), Times.format(at))
                                : SecretWrite.scannedTwice(graph, project, id, Times.format(at));
                ScanGraph.from(
                        graph,
                        node,
                        ScanGraph.sourceFile(graph, repo, file.path()),
                        secret.line(),
                        scan.commit());
                outcome.secrets.add(id);
            }
        }
        return outcome;
    }

    Outcome file(GraphWrite graph, ScannedFile file) {
        return new ScanFileWrite(this, graph, file).apply();
    }

    /** What the files of this scan found, for the close step of each file. */
    ScanBacking backing() {
        if (backing == null) {
            backing = new ScanBacking(this);
        }
        return backing;
    }

    /**
     * One conflict Signal ABOUT entity {@code id} for each dispute of the hint {@code entity}
     * (null: the project) whose kept value {@code path} holds first (ScanSlots), so each is raised
     * once.
     */
    void raiseDisputes(GraphWrite graph, String path, @Nullable String entity, String id) {
        for (ScanSlots.Dispute dispute : disputes) {
            if (Objects.equals(dispute.slot().entity(), entity)
                    && dispute.keptPath().equals(path)) {
                new ConflictSignals(graph)
                        .raise(graph.tx.findNode(ScanGraph.ENTITY, GraphWrite.ID, id), dispute, at);
            }
        }
    }

    /**
     * Runs after every file: the close step for the files of the repo the scan did not report
     * (gone, or nothing extracted). A file the scanner skipped ({@code <path>: <reason>}) stays as
     * it was.
     */
    Outcome unreported(GraphWrite graph) {
        Set<String> kept = new HashSet<>();
        scan.files().forEach(file -> kept.add(file.path()));
        scan.skipped()
                .forEach(line -> kept.add(line.substring(0, Math.max(0, line.lastIndexOf(": ")))));
        Outcome outcome = new Outcome();
        outcome.closed =
                ScanGraph.closeUnreported(
                        graph,
                        graph.tx.findNode(ScanGraph.ENTITY, GraphWrite.ID, projectId),
                        graph.tx.findNode(ScanGraph.REPO, GraphWrite.ID, scan.repo()),
                        kept,
                        at);
        return outcome;
    }

    void merge(Outcome outcome) {
        total.newEntities.addAll(outcome.newEntities);
        total.secrets.addAll(outcome.secrets);
        total.findings.addAll(outcome.findings);
        total.skipped.addAll(outcome.skipped);
        total.added += outcome.added;
        total.refreshed += outcome.refreshed;
        total.closed += outcome.closed;
        total.conflicts += outcome.conflicts;
    }

    /** A file whose transaction was rolled back; the reason holds no value (invariant 2). */
    void skip(String path, String reason) {
        total.skipped.add(path + ": " + reason);
    }

    ScanResult result() {
        List<String> skipped = new ArrayList<>(scan.skipped());
        skipped.addAll(total.skipped);
        return new ScanResult(
                projectId,
                scan.repo(),
                batch,
                scan.files().size(),
                total.newEntities,
                total.added,
                total.refreshed,
                total.closed,
                total.conflicts,
                total.secrets,
                total.findings,
                skipped);
    }

    // A secret id is <entity>/<env>/<name> (SecretWrite); a part may not hold the separator.
    private static boolean isIdPart(String part) {
        if (part.isEmpty() || part.contains("/")) {
            return false;
        }
        try {
            WriteCheck.text("secret id part", part, WriteCheck.ID_LIMIT);
            return true;
        } catch (WriteRejectedException e) {
            return false;
        }
    }
}
