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
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A new temporary file only the user kb runs as may read, for {@code kb run --file}
 * (docs/SECRETS.md §3, ADR-0027 decision 3). 0600 from its creation on POSIX. On NTFS the temp dir
 * must belong to that user, and the file gets the user's one ACL entry at its creation, so it is
 * never open to others, not even empty. Where the ACL cannot be set at creation the file inherits
 * the dir's, so only a dir that admits the user alone is used. A file system with neither is
 * refused. The directory counterpart is the server's {@code OwnerOnly}: kb may depend only on
 * kb-api (ARCHITECTURE §2), so the rule is written twice and both copies are tested.
 */
final class OwnerOnly {

    private static final FileAttribute<Set<PosixFilePermission>> POSIX_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final String ACL_ATTRIBUTE = "acl:acl";

    private OwnerOnly() {}

    /** {@code Files.createTempFile(dir, prefix, "", attributes)}; a seam for the tests. */
    @FunctionalInterface
    interface TempFiles {
        Path create(Path dir, String prefix, FileAttribute<?>... attributes) throws IOException;
    }

    /**
     * @param prefix the start of the file name; the JDK adds a random part
     */
    static Path createFile(Path dir, String prefix) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(dir, prefix, "", POSIX_FILE);
        }
        AclFileAttributeView dirAcl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (dirAcl == null) {
            throw new IOException(
                    dir
                            + " is on a file system with neither POSIX permissions nor ACLs, so"
                            + " kb cannot write a secret value there for the owner alone.");
        }
        UserPrincipal user =
                dir.getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
        return createAclFile(
                dir,
                prefix,
                user,
                dirAcl,
                (in, start, attributes) -> Files.createTempFile(in, start, "", attributes));
    }

    static Path createAclFile(
            Path dir,
            String prefix,
            UserPrincipal user,
            AclFileAttributeView dirAcl,
            TempFiles files)
            throws IOException {
        if (!dirAcl.getOwner().equals(user)) {
            throw new IOException(
                    "the temp dir "
                            + dir
                            + " belongs to another account than the one kb runs as; set TMP to a"
                            + " directory of your own.");
        }
        try {
            return files.create(dir, prefix, userOnly(user));
        } catch (UnsupportedOperationException e) {
            if (!dirAcl.getAcl().stream()
                    .allMatch(
                            entry ->
                                    entry.type() != AclEntryType.ALLOW
                                            || entry.principal().equals(user))) {
                throw new IOException(
                        "this file system cannot set an ACL on a new file, and the temp dir "
                                + dir
                                + " admits others; set TMP to a directory only you can enter.",
                        e);
            }
            return files.create(dir, prefix);
        }
    }

    private static FileAttribute<List<AclEntry>> userOnly(UserPrincipal user) {
        List<AclEntry> acl =
                List.of(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(user)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build());
        return new FileAttribute<>() {
            @Override
            public String name() {
                return ACL_ATTRIBUTE;
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }
}
