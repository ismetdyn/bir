package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0027 decision 3: the {@code kb run --file} file holds a secret value, so only its owner may
 * read it: 0600 on POSIX, an ACL with the owner as its only entry on NTFS, refused elsewhere. The
 * ACL branch runs on Linux through a fake view; the real one runs only on Windows.
 */
class OwnerOnlyFileTest {

    private static final UserPrincipal OWNER = () -> "owner";
    private static final UserPrincipal EVERYONE = () -> "Everyone";

    @TempDir Path dir;

    private static final class FakeAcl implements AclFileAttributeView {
        private List<AclEntry> acl;

        FakeAcl(List<AclEntry> acl) {
            this.acl = new ArrayList<>(acl);
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public UserPrincipal getOwner() {
            return OWNER;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            throw new UnsupportedOperationException("owner-only never changes the owner");
        }

        @Override
        public List<AclEntry> getAcl() {
            return List.copyOf(acl);
        }

        @Override
        public void setAcl(List<AclEntry> acl) {
            this.acl = new ArrayList<>(acl);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_posix_file_is_created_0600_with_the_prefix() throws IOException {
        Path file = OwnerOnly.createFile(dir, "kb-secret-42-");

        assertThat(file.getParent()).isEqualTo(dir);
        assertThat(file.getFileName().toString()).startsWith("kb-secret-42-");
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void an_acl_file_keeps_one_entry_the_owner_with_full_control() throws IOException {
        FakeAcl acl =
                new FakeAcl(
                        List.of(
                                AclEntry.newBuilder()
                                        .setType(AclEntryType.ALLOW)
                                        .setPrincipal(EVERYONE)
                                        .setPermissions(AclEntryPermission.READ_DATA)
                                        .build()));

        OwnerOnly.restrict(dir, acl);

        assertThat(acl.getAcl())
                .containsExactly(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(OWNER)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .build());
    }

    @Test
    void a_file_system_without_acls_or_posix_permissions_is_refused() {
        assertThatThrownBy(() -> OwnerOnly.restrict(dir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("neither POSIX permissions nor ACLs");
    }

    /** ADR-0027 decision 3 on NTFS: run on the Windows test machine (W0). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_file_is_the_owners_only() throws IOException {
        Path file = OwnerOnly.createFile(dir, "kb-secret-42-");

        assertThat(Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl())
                .extracting(AclEntry::principal)
                .containsOnly(Files.getOwner(file));
    }
}
