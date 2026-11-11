package io.github.ismetdyn.agentkb.server.graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A directory only the user agentkb runs as may enter (docs/SECRETS.md §5, ADR-0027 decision 2):
 * the data directory holds secret values in plain text. POSIX 0700 where the file system has POSIX
 * permissions. On NTFS: refused if the directory belongs to another account; else one ACL entry
 * that gives the user full control and is inherited inside, the parent's inheritance cut ({@code
 * icacls /inheritance:r}) so a later grant on the parent cannot reach in, and the entries below
 * reset to inherit it. Checked after it is made, so a file system that ignores the change stops the
 * start; one with neither POSIX permissions nor ACLs is refused outright. kb-client holds the file
 * counterpart for {@code kb run --file}: it may depend only on kb-api (ARCHITECTURE §2).
 */
public final class OwnerOnly {

    private static final Set<PosixFilePermission> DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    // icacls marks an entry it got from the parent with (I).
    private static final String INHERITED = "(I)";

    private OwnerOnly() {}

    /** icacls.exe with these arguments; its output, or an IOException unless it exits 0. */
    @FunctionalInterface
    interface Icacls {
        String run(List<String> args) throws IOException;
    }

    /** What the NTFS branch needs from Windows: the user agentkb runs as, and icacls. */
    record Ntfs(UserPrincipal user, Icacls icacls) {

        private static final Duration TIMEOUT = Duration.ofSeconds(60);

        static Ntfs ofThisMachine(FileSystem fileSystem) throws IOException {
            // An absolute path from SystemRoot: a bare icacls would be looked up in the PATH.
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                throw new IOException(
                        "SystemRoot is not set, so icacls.exe cannot be found and the data"
                                + " directory cannot be made owner-only");
            }
            Path exe = Path.of(systemRoot, "System32", "icacls.exe");
            UserPrincipal user =
                    fileSystem
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
            return new Ntfs(user, args -> run(exe, args, TIMEOUT));
        }

        /** Runs {@code exe} without a shell; fails closed on a non-zero exit or an overrun. */
        static String run(Path exe, List<String> args, Duration timeout) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(exe.toString());
            command.addAll(args);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process));
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException(exe.getFileName() + " did not finish within " + timeout);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(exe.getFileName() + " was interrupted", e);
            }
            if (process.exitValue() != 0) {
                throw new IOException(
                        exe.getFileName() + " failed with exit " + process.exitValue());
            }
            return output.join();
        }

        // ISO-8859-1 maps every byte; only the ASCII marker (I) is looked for.
        private static String read(Process process) {
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Creates {@code dir} if needed and makes it the user's only; a wider one is tightened.
     *
     * @throws IOException if it cannot be made so, or is not so afterwards
     */
    public static void directory(Path dir) throws IOException {
        Files.createDirectories(dir);
        PosixFileAttributeView posix =
                Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(DIRECTORY);
        } else {
            restrictAcl(dir, aclView(dir), Ntfs.ofThisMachine(dir.getFileSystem()));
        }
        if (!holds(dir.toRealPath())) {
            throw new IOException(
                    dir
                            + " is still open to others after it was made owner-only; its file"
                            + " system ignores the change. Use a directory on a local disk.");
        }
    }

    /** Whether {@code dir} itself (not a link's target) is the user's only, as made above. */
    public static boolean holds(Path dir) throws IOException {
        PosixFileAttributeView posix =
                Files.getFileAttributeView(
                        dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            return posix.readAttributes().permissions().equals(DIRECTORY);
        }
        AclFileAttributeView acl =
                Files.getFileAttributeView(
                        dir, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return acl != null && holdsAcl(dir, acl, Ntfs.ofThisMachine(dir.getFileSystem()));
    }

    static void restrictAcl(Path dir, AclFileAttributeView acl, Ntfs ntfs) throws IOException {
        if (!acl.getOwner().equals(ntfs.user())) {
            throw new IOException(
                    dir
                            + " belongs to another account than the one agentkb runs as; agentkb"
                            + " keeps secret values only in a directory of its own user.");
        }
        acl.setAcl(userOnly(ntfs.user()));
        ntfs.icacls().run(List.of(dir.toString(), "/inheritance:r"));
        boolean hasEntries;
        try (Stream<Path> entries = Files.list(dir)) {
            hasEntries = entries.findAny().isPresent();
        }
        // icacls takes Windows paths; an empty directory has no * to match and would fail.
        if (hasEntries) {
            ntfs.icacls().run(List.of(dir + "\\*", "/reset", "/T", "/C", "/Q"));
        }
    }

    static boolean holdsAcl(Path dir, AclFileAttributeView acl, Ntfs ntfs) throws IOException {
        return acl.getOwner().equals(ntfs.user())
                && acl.getAcl().equals(userOnly(ntfs.user()))
                && !ntfs.icacls().run(List.of(dir.toString())).contains(INHERITED);
    }

    private static AclFileAttributeView aclView(Path dir) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException(
                    dir
                            + " is on a file system with neither POSIX permissions nor ACLs, so it"
                            + " cannot be made owner-only; agentkb keeps secret values only where"
                            + " the owner alone can read them. Use a directory on a local disk.");
        }
        return acl;
    }

    private static List<AclEntry> userOnly(UserPrincipal user) {
        return List.of(
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(user)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        .build());
    }
}
