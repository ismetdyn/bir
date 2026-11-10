package io.github.ismetdyn.agentkb.core;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where a document's bytes live in the knowledge repo: {@code blobs/<first 2>/<sha256>} (SPEC
 * §6.7). The one place of that layout, for the writer, the reader and kb check. A sha256 that came
 * with a snapshot from another machine is not trusted, so only 64 lowercase hex digits make a path:
 * nothing else can lead out of blobs/. blobs/ also fills from a kb sync pull, so a path is handed
 * out only for a regular file in real directories, never through a link (CWE-59).
 */
public final class BlobPaths {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private BlobPaths() {}

    /** The blob path of {@code sha256}; empty unless it is 64 lowercase hex digits. */
    public static Optional<String> of(String sha256) {
        if (!SHA256.matcher(sha256).matches()) {
            return Optional.empty();
        }
        return Optional.of("blobs/" + sha256.substring(0, 2) + "/" + sha256);
    }

    /**
     * The blob of {@code sha256} in {@code repo} if blobs/ and blobs/xx are real directories and
     * the blob a regular file, none of them a link; the bytes are not read. Empty otherwise.
     */
    public static Optional<Path> regularFile(Path repo, String sha256) {
        return of(sha256)
                .map(repo::resolve)
                .filter(
                        blob ->
                                Files.isDirectory(
                                                blob.getParent().getParent(),
                                                LinkOption.NOFOLLOW_LINKS)
                                        && Files.isDirectory(
                                                blob.getParent(), LinkOption.NOFOLLOW_LINKS)
                                        && Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS));
    }
}
