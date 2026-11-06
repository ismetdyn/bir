package io.github.ismetdyn.agentkb.server.docs;

import io.github.ismetdyn.agentkb.server.write.DocumentWrite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files under {@code blobs/} (SPEC §6.7, ADR-0025 decision 2). blobs/ also fills from a kb sync
 * pull, so nothing there is trusted (CWE-59): a blob counts only if it is a regular file in real
 * directories whose bytes hash to its name, and a blob is written without following a link.
 */
final class Blobs {

    private static final Logger LOG = LoggerFactory.getLogger(Blobs.class);

    private Blobs() {}

    /**
     * Whether {@code repo} holds the blob of {@code sha}: a regular file, no link, in real
     * directories, whose bytes hash to its name. blobs/ also fills from a kb sync pull, so a blob
     * that does not hash to its name counts as absent, and storing the upload replaces it.
     */
    static boolean intact(Path repo, String sha) throws IOException {
        Path blob = repo.resolve(DocumentWrite.path(sha));
        if (!Files.isDirectory(blob.getParent().getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(blob.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        MessageDigest digest = sha256();
        try (InputStream bytes =
                new DigestInputStream(
                        Files.newInputStream(blob, LinkOption.NOFOLLOW_LINKS), digest)) {
            bytes.transferTo(OutputStream.nullOutputStream());
        }
        if (HexFormat.of().formatHex(digest.digest()).equals(sha)) {
            return true;
        }
        LOG.warn("event=blob_mismatch id={}", DocumentWrite.id(sha));
        return false;
    }

    /**
     * {@code .tmp} beside the blob, then ATOMIC_MOVE: a blob is whole or absent. The move replaces
     * a blob that did not hash to its name, or a link in its place, not the link's target.
     */
    static void write(FileChannel from, long length, Path repo, String sha) throws IOException {
        Path blob = repo.resolve(DocumentWrite.path(sha));
        Files.createDirectories(repo);
        realDirectory(blob.getParent().getParent());
        realDirectory(blob.getParent());
        Path tmp = blob.resolveSibling(blob.getFileName() + ".tmp");
        // A .tmp left over, or planted as a link: removed, never written through.
        Files.deleteIfExists(tmp);
        try {
            try (FileChannel to =
                    FileChannel.open(
                            tmp,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
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

    /** {@code dir} as a directory of its own, created if missing; a link or file is refused. */
    private static void realDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            throw new IOException(
                    "a link or file stands where the document store needs a directory", e);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
