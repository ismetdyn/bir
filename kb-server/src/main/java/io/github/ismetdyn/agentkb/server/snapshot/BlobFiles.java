package io.github.ismetdyn.agentkb.server.snapshot;

import io.github.ismetdyn.agentkb.core.BlobPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The state of a blob under {@code blobs/} (SPEC §6.7). blobs/ also fills from a kb sync pull, so
 * nothing there is trusted (CWE-59): a blob is intact only as a regular file in real directories,
 * not reached through a link, whose bytes hash to its name. Used by the document store and by kb
 * check; the bytes are hashed, never kept or shown.
 */
public final class BlobFiles {

    public enum State {
        INTACT,
        /** Nothing stands at the blob's path. */
        MISSING,
        /** Something stands there that is no regular file in real directories: a link, a dir. */
        NOT_A_FILE,
        /** A regular file whose bytes do not hash to its name. */
        MISMATCH
    }

    private BlobFiles() {}

    /**
     * @param sha256 64 lowercase hex digits
     * @throws IllegalArgumentException if {@code sha256} is not
     * @throws IOException if the blob cannot be read
     */
    public static State state(Path repo, String sha256) throws IOException {
        Path blob =
                repo.resolve(
                        BlobPaths.of(sha256)
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "a blob name is a lowercase sha256")));
        if (Files.notExists(blob, LinkOption.NOFOLLOW_LINKS)) {
            return State.MISSING;
        }
        if (BlobPaths.regularFile(repo, sha256).isEmpty()) {
            return State.NOT_A_FILE;
        }
        MessageDigest digest = sha256();
        try (InputStream bytes =
                new DigestInputStream(
                        Files.newInputStream(blob, LinkOption.NOFOLLOW_LINKS), digest)) {
            bytes.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest()).equals(sha256)
                ? State.INTACT
                : State.MISMATCH;
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
