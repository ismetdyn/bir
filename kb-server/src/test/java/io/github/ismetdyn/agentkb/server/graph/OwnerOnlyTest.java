package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0027 decision 2: the data directory holds secret values in plain text, so only its owner may
 * enter it. POSIX 0700 where the file system has POSIX permissions; an ACL with the owner as its
 * only entry where it has ACLs (NTFS); refused where it has neither. The ACL branch runs on Linux
 * through a fake view; the real one runs only on Windows.
 */
class OwnerOnlyTest {

    private static final UserPrincipal OWNER = () -> "owner";
    private static final UserPrincipal EVERYONE = () -> "Everyone";

    @TempDir Path parent;

    /** An ACL view held in memory, as the JDK's NTFS view answers it. */
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

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(who)
                .setPermissions(permissions)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_posix_directory_becomes_0700_and_a_wider_one_is_tightened() throws IOException {
        Path created = parent.resolve("new").resolve("agentkb");
        Path wide = Files.createDirectory(parent.resolve("wide"));
        Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwxr-xr-x"));

        OwnerOnly.directory(created);
        OwnerOnly.directory(wide);

        assertThat(Files.getPosixFilePermissions(created))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(wide))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(OwnerOnly.holds(created)).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void holds_is_false_for_a_posix_directory_others_can_enter() throws IOException {
        Path wide = Files.createDirectory(parent.resolve("wide"));
        Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwx--x---"));

        assertThat(OwnerOnly.holds(wide)).isFalse();
    }

    @Test
    void an_acl_directory_keeps_one_entry_the_owner_with_full_control_inherited_by_children()
            throws IOException {
        FakeAcl acl =
                new FakeAcl(
                        List.of(
                                allow(EVERYONE, AclEntryPermission.READ_DATA),
                                allow(OWNER, AclEntryPermission.READ_DATA)));

        OwnerOnly.restrict(parent, null, acl);

        assertThat(acl.getAcl())
                .containsExactly(
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(OWNER)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .build());
        assertThat(OwnerOnly.holds(null, acl)).isTrue();
    }

    @Test
    void holds_is_false_for_an_acl_with_any_other_entry() throws IOException {
        FakeAcl acl = new FakeAcl(List.of());
        OwnerOnly.restrict(parent, null, acl);
        List<AclEntry> widened = new ArrayList<>(acl.getAcl());
        widened.add(allow(EVERYONE, AclEntryPermission.READ_DATA));
        acl.setAcl(widened);

        assertThat(OwnerOnly.holds(null, acl)).isFalse();
    }

    @Test
    void a_file_system_with_neither_permissions_nor_acls_is_refused() {
        assertThatThrownBy(() -> OwnerOnly.restrict(parent, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(parent.toString())
                .hasMessageContaining("neither POSIX permissions nor ACLs");
    }

    /**
     * A zip file system offers neither view, so the server refuses to start on it instead of
     * putting secret values where others may read them.
     */
    @Test
    void the_store_refuses_to_start_on_a_file_system_without_owner_only_access()
            throws IOException {
        URI zip = URI.create("jar:" + parent.resolve("data.zip").toUri());
        try (FileSystem zipFs = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path dataDir = zipFs.getPath("/agentkb");

            assertThat(OwnerOnly.holds(dataDir)).isFalse();
            assertThatThrownBy(() -> new EmbeddedNeo4jStore(dataDir))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("owner-only")
                    .cause()
                    .hasMessageContaining("neither POSIX permissions nor ACLs");
        }
    }

    /** ADR-0027 decision 2 on NTFS: run on the Windows test machine (W0). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_directory_and_what_is_created_in_it_are_the_owners_only()
            throws IOException, InterruptedException {
        Path dir = parent.resolve("agentkb");

        OwnerOnly.directory(dir);
        Path child = Files.writeString(dir.resolve("child.txt"), "x");

        UserPrincipal owner = Files.getOwner(dir);
        assertThat(OwnerOnly.holds(dir)).isTrue();
        assertThat(Files.getFileAttributeView(child, AclFileAttributeView.class).getAcl())
                .extracting(AclEntry::principal)
                .containsOnly(owner);
        Process acl =
                new ProcessBuilder(
                                "powershell",
                                "-NoProfile",
                                "-Command",
                                "(Get-Acl -LiteralPath '" + dir + "').AreAccessRulesProtected")
                        .redirectErrorStream(true)
                        .start();
        String protectedRules = new String(acl.getInputStream().readAllBytes()).strip();
        assertThat(acl.waitFor()).isZero();
        assertThat(protectedRules).as("inheritance from the parent is off").isEqualTo("True");
    }
}
