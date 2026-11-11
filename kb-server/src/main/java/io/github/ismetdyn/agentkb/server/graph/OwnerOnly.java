package io.github.ismetdyn.agentkb.server.graph;

import java.io.IOException;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A directory only its owner may enter (docs/SECRETS.md §5, ADR-0027 decision 2): the data
 * directory holds secret values in plain text. POSIX 0700 where the file system has POSIX
 * permissions; where it has ACLs instead (NTFS), one entry that gives the file's owner full control
 * and is inherited by everything created inside, replacing the inherited entries. A file system
 * with neither is refused, so the secret invariant never weakens silently. kb-client holds the file
 * counterpart for {@code kb run --file}: it may depend only on kb-api (ARCHITECTURE §2).
 */
public final class OwnerOnly {

    private static final Set<PosixFilePermission> DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private OwnerOnly() {}

    /** Creates {@code dir} if needed and makes it the owner's only; a wider one is tightened. */
    public static void directory(Path dir) throws IOException {
        Files.createDirectories(dir);
        restrict(
                dir,
                Files.getFileAttributeView(dir, PosixFileAttributeView.class),
                Files.getFileAttributeView(dir, AclFileAttributeView.class));
    }

    /** Whether {@code dir} itself (not a link's target) is the owner's only, as made above. */
    public static boolean holds(Path dir) throws IOException {
        return holds(
                Files.getFileAttributeView(
                        dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS),
                Files.getFileAttributeView(
                        dir, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS));
    }

    static void restrict(
            Path dir, @Nullable PosixFileAttributeView posix, @Nullable AclFileAttributeView acl)
            throws IOException {
        if (posix != null) {
            posix.setPermissions(DIRECTORY);
        } else if (acl != null) {
            acl.setAcl(ownerOnly(acl.getOwner()));
        } else {
            throw new IOException(
                    dir
                            + " is on a file system with neither POSIX permissions nor ACLs, so it"
                            + " cannot be made owner-only; agentkb keeps secret values only where"
                            + " the owner alone can read them. Use a directory on a local disk.");
        }
    }

    static boolean holds(@Nullable PosixFileAttributeView posix, @Nullable AclFileAttributeView acl)
            throws IOException {
        if (posix != null) {
            return posix.readAttributes().permissions().equals(DIRECTORY);
        }
        return acl != null && acl.getAcl().equals(ownerOnly(acl.getOwner()));
    }

    private static List<AclEntry> ownerOnly(UserPrincipal owner) {
        return List.of(
                AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        .build());
    }
}
