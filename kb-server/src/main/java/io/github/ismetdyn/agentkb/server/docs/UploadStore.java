package io.github.ismetdyn.agentkb.server.docs;

import io.github.ismetdyn.agentkb.core.BlobPaths;
import io.github.ismetdyn.agentkb.server.write.DocumentWrite;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Upload staging, the server half of kb doc add before POST /api/v1/documents (ADR-0025 decision
 * 1). An upload is one file {@code <data-dir>/uploads/<sha>.<length>}: its name holds the declared
 * length, its size is the offset received so far. Bodies are streamed to disk, never held on the
 * heap. Staging does not compare the bytes with the sha256 (H4 does).
 *
 * <p>Only a lowercase sha256 names an upload, so no request value ever reaches a path otherwise.
 * Files idle (by modification time) for more than 7 days are removed at startup and on every PUT;
 * there is no timer (ADR-0025 "Alternatifler").
 */
@Component
public class UploadStore {

    /** What a PUT found; {@link Answer#offset()} is the bytes received so far. */
    public enum Start {
        CREATED,
        /** Staged earlier, or already complete in the blob store. */
        EXISTING,
        /** The same sha256 is staged or stored with another length. */
        LENGTH_CONFLICT,
        NO_SPACE,
        NEEDS_GIT_LFS
    }

    /** What a PATCH did; {@link Answer#offset()} is the bytes received after it. */
    public enum Append {
        APPENDED,
        /** The client's offset is not the received length; nothing written. */
        OFFSET_CONFLICT,
        /** Another PATCH of this upload is in progress; nothing written. */
        BUSY,
        /** The body runs past the declared length; nothing written. */
        PAST_LENGTH,
        /** No PUT started this upload, or it was deleted. */
        UNKNOWN
    }

    public record Answer<O>(O outcome, long offset) {}

    private static final Logger LOG = LoggerFactory.getLogger(UploadStore.class);
    private static final Duration IDLE_LIMIT = Duration.ofDays(7);
    // "MB" in ADR-0025 decision 5 and ARCHITECTURE §14 read as SI megabytes.
    private static final long BYTES_PER_MB = 1_000_000L;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final Path staging;
    private final @Nullable Path repo;
    private final long lfsThresholdBytes;

    public UploadStore(
            @Value("${agentkb.data-dir}") Path dataDir,
            @Value("${agentkb.repo:}") String repo,
            @Value("${agentkb.documents.lfs-threshold-mb}") long lfsThresholdMb)
            throws IOException {
        this.staging = Files.createDirectories(dataDir.resolve("uploads"));
        this.repo = repo.isBlank() ? null : Path.of(repo);
        this.lfsThresholdBytes = lfsThresholdMb * BYTES_PER_MB;
        removeIdle();
    }

    public static boolean isSha256(String id) {
        return BlobPaths.of(id).isPresent();
    }

    /**
     * PUT: starts an upload of {@code length} bytes or reports how far it got. The space check
     * comes before the git-lfs one: a file that does not fit cannot be stored either way.
     */
    // ponytail: one lock for every PUT and DELETE; they only touch metadata, PATCH does not take
    // it.
    public synchronized Answer<Start> start(String sha, long length) throws IOException {
        requireSha256(sha);
        removeIdle();
        if (repo != null && Blobs.intact(repo, sha)) {
            long stored = Files.size(repo.resolve(DocumentWrite.path(sha)));
            return new Answer<>(stored == length ? Start.EXISTING : Start.LENGTH_CONFLICT, stored);
        }
        Optional<Path> staged = staged(sha);
        if (staged.isPresent()) {
            long received = Files.size(staged.get());
            return new Answer<>(
                    declaredLength(staged.get()) == length ? Start.EXISTING : Start.LENGTH_CONFLICT,
                    received);
        }
        if (length > Files.getFileStore(staging).getUsableSpace()) {
            return new Answer<>(Start.NO_SPACE, 0);
        }
        if (needsGitLfs(length) && !GitLfs.present()) {
            return new Answer<>(Start.NEEDS_GIT_LFS, 0);
        }
        Files.createFile(staging.resolve(sha + "." + length));
        return new Answer<>(Start.CREATED, 0);
    }

    /**
     * PATCH: appends {@code body} at {@code offset}. The file lock is held while the body is read,
     * so a second PATCH of the same upload is refused instead of interleaving bytes; in this JVM
     * that shows as OverlappingFileLockException, from another process as no lock. The caller
     * bounds the body (RequestBodyLimitFilter, 1 MiB).
     */
    public Answer<Append> append(String sha, long offset, InputStream body) throws IOException {
        requireSha256(sha);
        Optional<Path> staged = staged(sha);
        if (staged.isEmpty()) {
            return new Answer<>(Append.UNKNOWN, 0);
        }
        long length = declaredLength(staged.get());
        try (FileChannel channel = FileChannel.open(staged.get(), StandardOpenOption.WRITE)) {
            @Nullable FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                return new Answer<>(Append.BUSY, 0);
            }
            long received = channel.size();
            if (offset != received) {
                return new Answer<>(Append.OFFSET_CONFLICT, received);
            }
            if (!copy(body, channel, length - received)) {
                channel.truncate(received);
                return new Answer<>(Append.PAST_LENGTH, received);
            }
            return new Answer<>(Append.APPENDED, channel.size());
        } catch (NoSuchFileException e) {
            // Deleted between the lookup and the open: the same as never started.
            return new Answer<>(Append.UNKNOWN, 0);
        }
    }

    /** DELETE: discards the upload; one that does not exist is already discarded. */
    public synchronized void discard(String sha) throws IOException {
        requireSha256(sha);
        Optional<Path> staged = staged(sha);
        if (staged.isPresent()) {
            Files.deleteIfExists(staged.get());
        }
    }

    /**
     * Writes {@code body} at the channel's end; false, with bytes possibly written, once it would
     * pass {@code room}. Reads at most one byte past {@code room}.
     */
    private static boolean copy(InputStream body, FileChannel channel, long room)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long position = channel.size();
        long written = 0;
        int read;
        while ((read = body.read(buffer, 0, (int) Math.min(buffer.length, room - written + 1)))
                > 0) {
            if (written + read > room) {
                return false;
            }
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
            while (bytes.hasRemaining()) {
                position += channel.write(bytes, position);
            }
            written += read;
        }
        return true;
    }

    /** A file over the threshold goes to git-lfs (ADR-0025 decision 5). */
    boolean needsGitLfs(long length) {
        return length > lfsThresholdBytes;
    }

    /** The staged file of {@code sha}, received bytes as its size; empty if none. */
    Optional<Path> staged(String sha) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(staging, sha + ".*")) {
            for (Path file : files) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    static long declaredLength(Path staged) {
        String name = staged.getFileName().toString();
        return Long.parseLong(name.substring(name.indexOf('.') + 1));
    }

    private void removeIdle() throws IOException {
        FileTime cutoff = FileTime.from(Instant.now().minus(IDLE_LIMIT));
        long removed = 0;
        try (Stream<Path> files = Files.list(staging)) {
            for (Path file : files.toList()) {
                if (Files.getLastModifiedTime(file).compareTo(cutoff) < 0
                        && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            LOG.info("event=uploads_expired count={}", removed);
        }
    }

    static void requireSha256(String sha) {
        if (!isSha256(sha)) {
            throw new IllegalArgumentException("an upload id is a lowercase sha256");
        }
    }
}
