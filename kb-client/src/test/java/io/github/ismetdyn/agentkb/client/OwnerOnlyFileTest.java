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
import java.nio.file.attribute.FileAttribute;
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
 * ADR-0027 decision 3: the {@code kb run --file} file holds a secret value, so only the user kb
 * runs as may read it. 0600 from its creation on POSIX. On NTFS the user's one ACL entry is given
 * at creation; a temp dir of another account is refused, and so is a file system that cannot set
 * the ACL at creation unless the temp dir already admits only the user. The NTFS branch runs on
 * Linux through fakes; the real one runs only on Windows.
 */
class OwnerOnlyFileTest {

    private static final UserPrincipal USER = () -> "ayse";
    private static final UserPrincipal OTHER = () -> "Administrators";

    @TempDir Path dir;

    private static final class FakeAcl implements AclFileAttributeView {
        private final UserPrincipal owner;
        private final List<AclEntry> acl;

        FakeAcl(UserPrincipal owner, List<AclEntry> acl) {
            this.owner = owner;
            this.acl = List.copyOf(acl);
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public UserPrincipal getOwner() {
            return owner;
        }

        @Override
        public void setOwner(UserPrincipal owner) {
            throw new UnsupportedOperationException("never changed");
        }

        @Override
        public List<AclEntry> getAcl() {
            return acl;
        }

        @Override
        public void setAcl(List<AclEntry> acl) {
            throw new UnsupportedOperationException("never changed");
        }
    }

    /** Files.createTempFile as the code calls it: the attributes of every call recorded. */
    private final class Creator implements OwnerOnly.TempFiles {
        final List<List<FileAttribute<?>>> calls = new ArrayList<>();
        boolean aclAtCreation = true;

        @Override
        public Path create(Path in, String prefix, FileAttribute<?>... attributes)
                throws IOException {
            calls.add(List.of(attributes));
            if (attributes.length > 0 && !aclAtCreation) {
                throw new UnsupportedOperationException("'acl:acl' not supported as initial");
            }
            return Files.createTempFile(dir, prefix, "");
        }
    }

    private static AclEntry allow(UserPrincipal who) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(who)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
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
    void an_ntfs_file_gets_the_users_one_entry_at_creation() throws IOException {
        Creator creator = new Creator();

        OwnerOnly.createAclFile(dir, "kb-secret-42-", USER, new FakeAcl(USER, List.of()), creator);

        assertThat(creator.calls).hasSize(1);
        FileAttribute<?> acl = creator.calls.getFirst().getFirst();
        assertThat(acl.name()).isEqualTo("acl:acl");
        assertThat(acl.value()).isEqualTo(List.of(allow(USER)));
    }

    @Test
    void a_temp_dir_of_another_account_is_refused_before_anything_is_created() {
        Creator creator = new Creator();

        assertThatThrownBy(
                        () ->
                                OwnerOnly.createAclFile(
                                        dir,
                                        "kb-secret-42-",
                                        USER,
                                        new FakeAcl(OTHER, List.of(allow(USER))),
                                        creator))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("another account");
        assertThat(creator.calls).isEmpty();
    }

    /** Without the ACL at creation the file inherits the dir's: only a dir of the user alone. */
    @Test
    void without_an_acl_at_creation_only_a_temp_dir_of_the_user_alone_is_used() throws IOException {
        Creator creator = new Creator();
        creator.aclAtCreation = false;

        Path file =
                OwnerOnly.createAclFile(
                        dir,
                        "kb-secret-42-",
                        USER,
                        new FakeAcl(USER, List.of(allow(USER))),
                        creator);

        assertThat(file).exists();
        assertThat(creator.calls).hasSize(2);
        assertThat(creator.calls.get(1)).isEmpty();
        assertThatThrownBy(
                        () ->
                                OwnerOnly.createAclFile(
                                        dir,
                                        "kb-secret-42-",
                                        USER,
                                        new FakeAcl(USER, List.of(allow(USER), allow(OTHER))),
                                        creator))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot set an ACL");
    }

    /** ADR-0027 decision 3 on NTFS: run on the Windows test machine (W0). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_file_is_the_users_only() throws IOException {
        Path file = OwnerOnly.createFile(dir, "kb-secret-42-");

        assertThat(Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl())
                .extracting(AclEntry::principal)
                .containsOnly(Files.getOwner(dir));
    }
}
