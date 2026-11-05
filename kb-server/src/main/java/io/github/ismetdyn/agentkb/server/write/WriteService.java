package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.Checked;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The only writer of the graph (invariant 1). */
@Component
public class WriteService {

    /** What stands for a secret value in a raw signal (docs/SECRETS.md). */
    public static final String MASK = "****";

    private final EmbeddedNeo4jStore store;
    private final RepoSchema schema;
    // Vocabulary types that already have their id constraint in this process (RelationWriteTest).
    private final Set<String> constrainedTypes = ConcurrentHashMap.newKeySet();

    public WriteService(EmbeddedNeo4jStore store, RepoSchema schema) {
        this.store = store;
        this.schema = schema;
    }

    /**
     * A new write from MCP or REST (ARCHITECTURE §5): checked first, then one transaction under the
     * writer lock, so a rejected request leaves the graph as it was. Without a batch in the request
     * the write is a batch of its own.
     *
     * @throws WriteRejectedException if the request breaks a rule; nothing was written
     * @throws IllegalStateException if the repo has no readable schema
     */
    public WriteResult write(WriteRequest request) {
        Checked checked = WriteCheck.check(request, schema.load());
        constrain(checked.relationTypes());
        String batch = request.batch() == null ? Ulid.next() : request.batch();
        Instant at = request.at().truncatedTo(ChronoUnit.SECONDS);
        return store.write(
                tx -> {
                    SecretWrite.refuseStoredValues(tx, SecretWrite.texts(request));
                    return new RequestWrite(
                                    new GraphWrite(tx, batch, Instant.now()), request, checked, at)
                            .apply();
                });
    }

    /**
     * Writes one scan of a working copy (ARCHITECTURE §6, ADR-0022 decision 31): first the project,
     * its Repo and the secrets, then one transaction per file. All of it is one batch, so {@code kb
     * revert --batch} undoes the scan. A file the write path refuses as a whole is rolled back and
     * reported in {@link ScanResult#skipped()}; a single refused value is skipped alone.
     *
     * @throws WriteRejectedException if the project or repo id is refused; nothing was written
     * @throws IllegalStateException if the repo has no readable schema
     */
    public ScanResult scan(ScanWrite scan) {
        ScanWrites writes =
                new ScanWrites(
                        scan,
                        schema.load(),
                        Ulid.next(),
                        scan.at().truncatedTo(ChronoUnit.SECONDS));
        constrain(writes.relationTypes());
        writes.merge(
                store.write(tx -> writes.open(new GraphWrite(tx, writes.batch, Instant.now()))));
        for (ScanWrite.ScannedFile file : writes.scan.files()) {
            try {
                writes.merge(
                        store.write(
                                tx ->
                                        writes.file(
                                                new GraphWrite(tx, writes.batch, Instant.now()),
                                                file)));
            } catch (WriteRejectedException e) {
                writes.skip(file.path(), e.getMessage());
            }
        }
        writes.merge(
                store.write(
                        tx -> writes.unreported(new GraphWrite(tx, writes.batch, Instant.now()))));
        return writes.result();
    }

    /**
     * Opens a Proposal (SPEC §7 rule 6); nothing else changes.
     *
     * @return the id of the proposal
     * @throws WriteRejectedException if the proposal breaks a rule; nothing was written
     */
    public String propose(ProposalRequest request) {
        ProposalWrite.check(request);
        return store.write(
                tx -> {
                    SecretWrite.refuseStoredValues(
                            tx,
                            Arrays.asList(
                                    request.kind(),
                                    request.target(),
                                    request.content(),
                                    request.evidence()));
                    return ProposalWrite.write(
                            new GraphWrite(tx, Ulid.next(), Instant.now()), request);
                });
    }

    /**
     * Sets the value of a Secret from {@code PUT /api/v1/secrets/<id>} (docs/SECRETS.md §2),
     * creating the Secret {@code <entity>/<env>/<name>} under its existing entity when it is new.
     *
     * @throws WriteRejectedException if the value is empty or a new id is malformed or names no
     *     entity; nothing was written
     */
    public void putSecret(String id, String value) {
        if (value.isBlank()) {
            throw new WriteRejectedException(
                    "the secret value is empty", "Send the value as the request body.");
        }
        store.write(
                tx -> SecretWrite.put(new GraphWrite(tx, Ulid.next(), Instant.now()), id, value));
    }

    /**
     * Raises a local Signal (ADR-0022 decision 7) such as a hook's {@code qa-capture}: a new one
     * opens with count 1, the same kind and key again raises its count. A value stored on a Secret
     * is masked as {@link #MASK} in key and payload first (docs/SECRETS.md); the caller masks what
     * only looks like a secret.
     */
    public void raiseSignal(String kind, String key, String payload, Instant at) {
        String seen = Times.format(at);
        store.write(
                tx ->
                        SignalWrite.raise(
                                new GraphWrite(tx, Ulid.next(), Instant.now()),
                                kind,
                                SecretWrite.maskStoredValues(tx, key, MASK),
                                SecretWrite.maskStoredValues(tx, payload, MASK),
                                seen));
    }

    /** Neo4j refuses schema and data changes in one transaction, so this runs before the write. */
    private void constrain(Set<String> relationTypes) {
        Set<String> missing = new TreeSet<>(relationTypes);
        missing.removeAll(constrainedTypes);
        if (!missing.isEmpty()) {
            GraphSchema.ensure(store, Set.of(), missing);
            constrainedTypes.addAll(missing);
        }
    }

    /**
     * Creates the fixed schema and brings the fulltext index to its current definition; called on
     * every start, whatever the graph holds (GraphSchema).
     */
    public void ensureSchema() {
        GraphSchema.ensure(store, Set.of(), Set.of());
    }

    /**
     * Loads snapshot rows as they are: no schema, key or length checks and {@code updated_at} kept
     * (invariant 11), all rows in one transaction. Fails, leaving the data unchanged, only when a
     * row cannot exist in a graph: a duplicate id or an edge whose endpoint is missing.
     */
    public void importRecords(Snapshot snapshot) {
        Set<String> labels = new TreeSet<>();
        snapshot.nodes().forEach(node -> labels.addAll(node.labels()));
        Set<String> types = new TreeSet<>();
        snapshot.edges().forEach(edge -> types.add(edge.type()));
        GraphSchema.ensure(store, labels, types);
        store.write(tx -> RecordImport.create(tx, snapshot));
    }
}
