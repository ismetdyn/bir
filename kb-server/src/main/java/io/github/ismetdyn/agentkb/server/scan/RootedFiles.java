package io.github.ismetdyn.agentkb.server.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Invariant 9 for reading: the allowed roots (real paths) and the read of one file under them. Both
 * checks hold for what was opened, not only for the path checked before: the real path is opened
 * from its root one name at a time without following a link, and the size is measured on the opened
 * channel, so a file swapped for a link or a large file between the check and the read is not read.
 */
final class RootedFiles {

    private static final Set<OpenOption> READ_NO_LINK =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private RootedFiles() {}

    // Path.startsWith compares whole names, so <root>-evil is not under <root>.
    static boolean isUnder(Path real, List<Path> roots) {
        return roots.stream().anyMatch(real::startsWith);
    }

    static boolean isRealUnder(Path path, List<Path> roots) {
        try {
            return isUnder(path.toRealPath(), roots);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The UTF-8 text of a regular file whose real path is under the roots, at most {@code
     * ScannerService.MAX_FILE_BYTES}; else null, with the reason added to {@code skipped} unless
     * the file does not exist.
     */
    static @Nullable String text(Path file, String path, List<Path> roots, List<String> skipped) {
        String reason;
        try {
            Path real = file.toRealPath();
            Optional<Path> root = roots.stream().filter(real::startsWith).findFirst();
            if (root.isEmpty()) {
                reason = "links outside the folders this scan may read";
            } else if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                reason = "not a regular file";
            } else {
                byte[] bytes = readAtMostMax(root.get(), real);
                if (bytes == null) {
                    reason = "larger than 1 MiB";
                } else {
                    // The decoder reports malformed input, as Files.readString did.
                    return StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
                }
            }
        } catch (CharacterCodingException e) {
            reason = "not UTF-8 text";
        } catch (NoSuchFileException e) {
            // Deleted but still in the git index: gone, so its records close like a deleted file's.
            return null;
        } catch (IOException e) {
            reason = "cannot be read";
        }
        skipped.add(path + ": " + reason);
        return null;
    }

    /** The bytes of {@code real}, null if the opened file holds more than MAX_FILE_BYTES. */
    private static byte @Nullable [] readAtMostMax(Path root, Path real) throws IOException {
        long max = ScannerService.MAX_FILE_BYTES;
        try (SeekableByteChannel channel = open(root, real)) {
            if (channel.size() > max) {
                return null;
            }
            // A file that grows after size() is still cut off one byte past the limit.
            byte[] bytes = Channels.newInputStream(channel).readNBytes((int) max + 1);
            return bytes.length > max ? null : bytes;
        }
    }

    /**
     * Opens {@code real}, a path under {@code root} without links, one name at a time from root
     * (openat with O_NOFOLLOW), so a folder or the file swapped for a link after toRealPath fails
     * to open instead of leading outside the roots.
     */
    private static SeekableByteChannel open(Path root, Path real) throws IOException {
        Path relative = root.relativize(real);
        try (DirectoryStream<Path> top = Files.newDirectoryStream(root)) {
            if (top instanceof SecureDirectoryStream<Path> secure) {
                return open(secure, relative, 0);
            }
        }
        // ponytail: a file system without openat only gets O_NOFOLLOW on the last name; a folder
        // swapped for a link in between could still lead outside. Linux has openat.
        return Files.newByteChannel(real, READ_NO_LINK);
    }

    private static SeekableByteChannel open(
            SecureDirectoryStream<Path> dir, Path relative, int index) throws IOException {
        Path name = relative.getName(index);
        if (index == relative.getNameCount() - 1) {
            return dir.newByteChannel(name, READ_NO_LINK);
        }
        try (SecureDirectoryStream<Path> sub =
                dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            return open(sub, relative, index + 1);
        }
    }
}
