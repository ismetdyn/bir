package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteCheck.Checked;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Transaction;
import org.springframework.stereotype.Component;

/**
 * The only writer of the graph (invariant 1): new knowledge here, the writes on the graph as a
 * whole through {@link #maintenance}.
 */
@Component
public class WriteService {

    /** What stands for a secret value in a raw signal (docs/SECRETS.md). */
    public static final String MASK = "****";

    private final EmbeddedNeo4jStore store;
    private final RepoSchema schema;
    // Vocabulary types that already have their id constraint in this process (RelationWriteTest).
    private final Set<String> constrainedTypes = ConcurrentHashMap.newKeySet();

    /** Schema, import, revert, restore and sync merge; reached by this package only. */
    final GraphMaintenance maintenance;

    public WriteService(EmbeddedNeo4jStore store, RepoSchema schema) {
        this.store = store;
        this.schema = schema;
        this.maintenance = new GraphMaintenance(store, schema);
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
        GraphSchema.ensureTypes(store, constrainedTypes, checked.relationTypes());
        String batch = request.batch() == null ? Ulid.next() : request.batch();
        Instant at = request.at().truncatedTo(ChronoUnit.SECONDS);
        return store.write(
                tx -> {
                    SecretWrite.refuseStoredValues(tx, SecretWrite.texts(request));
                    GraphWrite graph =
                            new GraphWrite(
                                    tx,
                                    batch,
                                    EpisodeWrite.known(tx, request.episode()).orElse(null),
                                    Instant.now());
                    return new RequestWrite(graph, request, checked, at).apply();
                });
    }

    /**
     * SessionStart (ADR-0023 decision 3): opens the Episode of {@code (client, sessionId)} or
     * refreshes its last_at. A session id or transcript path that is no plain short text is not
     * kept: an id is left without an Episode, a path is left out.
     *
     * @return the id of the Episode; empty if {@code sessionId} is no plain id
     */
    public Optional<String> openSession(
            String client, String sessionId, @Nullable String transcriptPath) {
        @Nullable String path = EpisodeWrite.transcriptPath(transcriptPath);
        return EpisodeWrite.sessionId(sessionId)
                .map(
                        id -> {
                            String at = Times.format(Instant.now());
                            return store.write(
                                    tx ->
                                            EpisodeWrite.session(
                                                    new GraphWrite(tx, Ulid.next(), Instant.now()),
                                                    client,
                                                    id,
                                                    path,
                                                    at));
                        });
    }

    /**
     * Writes one scan of a working copy (ARCHITECTURE §6, ADR-0022 decision 31): first the project,
     * its Repo and the secrets, then one transaction per file. All of it is one batch, so {@code kb
     * revert --batch} undoes the scan. A file the write path refuses as a whole is rolled back and
     * reported in {@link ScanResult#skipped()}; a single refused value is skipped alone.
     *
     * <p>Every record it opens carries {@code episode} if that names an Episode; otherwise the scan
     * opens an Episode of its own, kind and client scan (ADR-0023 decision 3).
     *
     * @throws WriteRejectedException if the project or repo id is refused; nothing was written
     * @throws IllegalStateException if the repo has no readable schema
     */
    public ScanResult scan(ScanWrite scan, @Nullable String episode) {
        ScanWrites writes =
                new ScanWrites(
                        scan,
                        schema.load(),
                        Ulid.next(),
                        scan.at().truncatedTo(ChronoUnit.SECONDS));
        GraphSchema.ensureTypes(store, constrainedTypes, writes.relationTypes());
        Optional<String> given = store.read(tx -> EpisodeWrite.known(tx, episode));
        String stamp = given.orElseGet(Ulid::next);
        // The scan's own Episode opens with the first record it stamps, in whichever transaction.
        @Nullable Map<String, Object> own =
                given.isPresent()
                        ? null
                        : EpisodeWrite.event(
                                EpisodeWrite.SCAN, EpisodeWrite.SCAN, Times.format(writes.at));
        Function<Transaction, GraphWrite> graph =
                tx -> new GraphWrite(tx, writes.batch, stamp, own, Instant.now());
        writes.merge(store.write(tx -> writes.open(graph.apply(tx))));
        for (ScanWrite.ScannedFile file : writes.scan.files()) {
            try {
                writes.merge(store.write(tx -> writes.file(graph.apply(tx), file)));
            } catch (WriteRejectedException e) {
                writes.skip(file.path(), e.getMessage());
            }
        }
        writes.merge(store.write(tx -> writes.unreported(graph.apply(tx))));
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
                            new GraphWrite(
                                    tx,
                                    Ulid.next(),
                                    EpisodeWrite.known(tx, request.episode()).orElse(null),
                                    Instant.now()),
                            request);
                });
    }

    /**
     * Sets the value of a Secret from {@code PUT /api/v1/secrets/<id>} (docs/SECRETS.md §2),
     * creating the Secret {@code <entity>/<env>/<name>} under its existing entity when it is new.
     * It is the user's own write, so it opens an Episode of kind user, client kb (ADR-0023).
     *
     * @throws WriteRejectedException if the value is empty or a new id is malformed or names no
     *     entity; nothing was written
     */
    public void putSecret(String id, String value) {
        if (value.isBlank()) {
            throw new WriteRejectedException(
                    "the secret value is empty", "Send the value as the request body.");
        }
        Instant now = Instant.now();
        Map<String, Object> own =
                EpisodeWrite.event(EpisodeWrite.USER, EpisodeWrite.KB, Times.format(now));
        store.write(
                tx ->
                        SecretWrite.put(
                                new GraphWrite(tx, Ulid.next(), Ulid.next(), own, now), id, value));
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

    /**
     * Creates the fixed schema and brings the fulltext index to its current definition; called on
     * every start, whatever the graph holds (GraphSchema).
     */
    public void ensureSchema() {
        maintenance.ensureSchema();
    }

    /**
     * Loads snapshot rows as they are: no schema, key or length checks and {@code updated_at} kept
     * (invariant 11), all rows in one transaction; only an {@code id} or secret_value prop is
     * ignored (RecordImport, invariant 2). Fails, leaving the data unchanged, only when a row
     * cannot exist in a graph: a duplicate id or an edge whose endpoint is missing.
     */
    public void importRecords(Snapshot snapshot) {
        maintenance.importRecords(snapshot);
    }

    /**
     * {@code kb revert --batch <id>} (BatchRevert): deletes what the batch opened and reopens what
     * it closed, in one transaction.
     *
     * @throws WriteRejectedException if the batch is empty or unknown, or a later batch built on
     *     it; nothing changed
     */
    public RevertResult revert(@Nullable String batch) {
        return maintenance.revert(batch);
    }

    /**
     * {@code kb restore} (RecordRestore): rebuilds the graph from {@code snapshot} in one
     * transaction, keeping local data (ADR-0022 decision 38). Rows are taken as they are (invariant
     * 11). Fails, leaving the graph unchanged, only when a row cannot exist in a graph.
     */
    public RestoreResult replaceAll(Snapshot snapshot) {
        return maintenance.replaceAll(snapshot);
    }
}
