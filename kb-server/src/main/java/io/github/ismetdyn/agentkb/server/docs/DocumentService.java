package io.github.ismetdyn.agentkb.server.docs;

import io.github.ismetdyn.agentkb.core.secret.SecretScan;
import io.github.ismetdyn.agentkb.server.write.DocumentWrite;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Completion of an upload, {@code POST /api/v1/documents} (ADR-0025 decisions 2–5, SPEC §6.7): the
 * staged bytes must be complete, hash to their id and hold no secret-looking line; then they are
 * copied to {@code blobs/xx/<sha>.tmp} and moved into place with ATOMIC_MOVE, the staging is
 * removed, a blob over the threshold is tracked by git-lfs, and only then the Document node is
 * written through WriteService (invariant 1). Hash, scan and copy run outside the writer lock; only
 * the node waits for it. A refused upload is removed from staging; a blob is never deleted, so
 * content already in blobs/ (a reverted document, a sync from another machine) is added again
 * without an upload.
 */
@Component
public class DocumentService {

    public enum Outcome {
        CREATED,
        /** The content is already a Document; only new links were added. */
        EXISTING,
        /** No upload and no blob of this sha256. */
        UNKNOWN,
        /** Fewer bytes received than the upload's length; the staging is kept. */
        INCOMPLETE,
        /** The bytes do not hash to the id; the staging is removed. */
        HASH_MISMATCH,
        /** A line looks like it holds a secret; the staging is removed. */
        SECRET
    }

    /**
     * {@code id} and the absolute {@code path} of the blob are set for CREATED and EXISTING, {@code
     * line} (1-based) for SECRET.
     */
    public record Completion(
            Outcome outcome, @Nullable String id, @Nullable String path, long line) {}

    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);

    private final UploadStore uploads;
    private final WriteService writes;
    private final String repo;

    public DocumentService(
            UploadStore uploads, WriteService writes, @Value("${agentkb.repo:}") String repo) {
        this.uploads = uploads;
        this.writes = writes;
        this.repo = repo;
    }

    /**
     * @throws WriteRejectedException if there is no knowledge repo or name or origin is refused,
     *     and nothing was stored; or about or replaces is refused, and only the blob was stored
     * @throws IOException if the blob cannot be written or git-lfs fails; no node was written
     */
    // ponytail: one completion at a time, so two completions of one sha256 cannot race on its
    // staging, .tmp and node; per-sha locks if parallel adds of different files ever matter.
    public synchronized Completion complete(DocumentWrite document) throws IOException {
        if (repo.isBlank()) {
            throw new WriteRejectedException(
                    "agentkb.repo is not set; there is no knowledge repository for documents",
                    "Start the server with --agentkb.repo=<path of the knowledge repository>.");
        }
        // name and origin go to the snapshot and git: refused before anything is stored.
        document.checkFields();
        String sha = document.sha256();
        String path = DocumentWrite.path(sha);
        Path root = Path.of(repo);
        Path blob = root.resolve(path);
        if (!Files.isRegularFile(blob)) {
            Optional<Completion> refused = store(sha, blob);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        long size = Files.size(blob);
        if (uploads.needsGitLfs(size)) {
            GitLfs.track(root, path);
        }
        boolean created = writes.addDocument(document, size);
        LOG.info("event=document_completed created={} size={}", created, size);
        return new Completion(
                created ? Outcome.CREATED : Outcome.EXISTING,
                DocumentWrite.id(sha),
                blob.toAbsolutePath().toString(),
                0);
    }

    /** Moves a complete and clean upload into {@code blob}; otherwise why not. */
    private Optional<Completion> store(String sha, Path blob) throws IOException {
        Optional<Path> staged = uploads.staged(sha);
        if (staged.isEmpty()) {
            return Optional.of(refusal(Outcome.UNKNOWN, 0));
        }
        long length = UploadStore.declaredLength(staged.get());
        Optional<Completion> refused;
        // One channel for check and copy: the bytes checked are the bytes stored, even if the
        // upload is deleted and started again meanwhile.
        try (FileChannel channel = FileChannel.open(staged.get(), StandardOpenOption.READ)) {
            if (channel.size() != length) {
                return Optional.of(refusal(Outcome.INCOMPLETE, 0));
            }
            refused = check(channel, sha);
            if (refused.isEmpty()) {
                copy(channel, length, blob);
            }
        }
        uploads.discard(sha);
        return refused;
    }

    /** Hash and secret scan in one pass over the bytes. */
    private static Optional<Completion> check(FileChannel channel, String sha) throws IOException {
        MessageDigest digest = sha256();
        // Not closed: closing it would close the channel the copy still reads.
        InputStream bytes = new DigestInputStream(Channels.newInputStream(channel), digest);
        // The decoder replaces invalid UTF-8, so binary bytes do not stop the scan (decision 3).
        OptionalLong line =
                SecretScan.firstSecretLine(new InputStreamReader(bytes, StandardCharsets.UTF_8));
        if (line.isPresent()) {
            LOG.info("event=document_refused reason=secret_found");
            return Optional.of(refusal(Outcome.SECRET, line.getAsLong()));
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha)) {
            LOG.info("event=document_refused reason=hash_mismatch");
            return Optional.of(refusal(Outcome.HASH_MISMATCH, 0));
        }
        return Optional.empty();
    }

    /** {@code .tmp} beside the blob, then ATOMIC_MOVE: a blob is whole or absent. */
    private static void copy(FileChannel from, long length, Path blob) throws IOException {
        Files.createDirectories(blob.getParent());
        Path tmp = blob.resolveSibling(blob.getFileName() + ".tmp");
        try {
            try (FileChannel to =
                    FileChannel.open(
                            tmp,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                long done = 0;
                while (done < length) {
                    long moved = from.transferTo(done, length - done, to);
                    if (moved <= 0) {
                        throw new IOException("the staged file ended before its length");
                    }
                    done += moved;
                }
                to.force(true);
            }
            Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Completion refusal(Outcome outcome, long line) {
        return new Completion(outcome, null, null, line);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
