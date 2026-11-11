package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A new temporary file only its owner may read, for {@code kb run --file} (docs/SECRETS.md §3,
 * ADR-0027 decision 3): 0600 from its creation on POSIX; on NTFS one ACL entry that gives the
 * file's owner full control; refused on a file system with neither. The directory counterpart is
 * the server's {@code OwnerOnly}: kb may depend only on kb-api (ARCHITECTURE §2), so the rule is
 * written twice and both copies are tested.
 */
final class OwnerOnly {

    private static final FileAttribute<Set<PosixFilePermission>> POSIX_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private OwnerOnly() {}

    /**
     * @param prefix the start of the file name; the JDK adds a random part
     * @throws IOException if the file cannot be made owner-only; nothing is left behind then
     */
    static Path createFile(Path dir, String prefix) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(dir, prefix, "", POSIX_FILE);
        }
        // Empty until restricted, and the value is written only after. The JDK temp dir on
        // Windows is under the user's profile, which admits only the user, SYSTEM and the
        // administrators, so no other user can open the file in between.
        Path file = Files.createTempFile(dir, prefix, "");
        try {
            restrict(file, Files.getFileAttributeView(file, AclFileAttributeView.class));
            return file;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    static void restrict(Path file, @Nullable AclFileAttributeView acl) throws IOException {
        if (acl == null) {
            throw new IOException(
                    file
                            + " is on a file system with neither POSIX permissions nor ACLs, so it"
                            + " cannot be made owner-only; kb writes secret values only where the"
                            + " owner alone can read them.");
        }
        acl.setAcl(
                List.of(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(acl.getOwner())
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build()));
    }
}
