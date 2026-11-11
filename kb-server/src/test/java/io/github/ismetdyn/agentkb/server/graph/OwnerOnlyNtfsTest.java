package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0027 decision 2 on NTFS, through a fake ACL view and a recording icacls so it runs on Linux:
 * one ACL entry for the user agentkb runs as, inheritance from the parent cut and the entries below
 * reset, a directory of another account refused untouched, a failing icacls failing the start, and
 * holds() false for any inherited or extra entry. The real NTFS runs in OwnerOnlyTest on Windows.
 */
class OwnerOnlyNtfsTest {

    private static final UserPrincipal USER = () -> "ayse";
    private static final UserPrincipal OTHER = () -> "Administrators";
    private static final UserPrincipal EVERYONE = () -> "Everyone";
    private static final String NO_INHERITED = "C:\\data\\agentkb ayse:(OI)(CI)(F)\n";

    @TempDir Path parent;

    /** An ACL view held in memory, as the JDK's NTFS view answers it. */
    private static final class FakeAcl implements AclFileAttributeView {
        private final UserPrincipal owner;
        private List<AclEntry> acl;

        FakeAcl(UserPrincipal owner, List<AclEntry> acl) {
            this.owner = owner;
            this.acl = new ArrayList<>(acl);
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

    /** icacls as the code calls it: every argument list recorded, {@code output} answered. */
    private static final class Icacls {
        final List<List<String>> calls = new ArrayList<>();
        String output = NO_INHERITED;

        OwnerOnly.Ntfs ntfs() {
            return new OwnerOnly.Ntfs(
                    USER,
                    args -> {
                        calls.add(args);
                        return output;
                    });
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

    private static AclEntry userOnly() {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(USER)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
    }

    @Test
    void an_ntfs_directory_gets_one_entry_for_the_user_and_its_inheritance_cut()
            throws IOException {
        FakeAcl acl =
                new FakeAcl(
                        USER,
                        List.of(
                                allow(EVERYONE, AclEntryPermission.READ_DATA),
                                allow(USER, AclEntryPermission.READ_DATA)));
        Icacls icacls = new Icacls();

        OwnerOnly.restrictAcl(parent, acl, icacls.ntfs());

        assertThat(acl.getAcl()).containsExactly(userOnly());
        assertThat(icacls.calls).containsExactly(List.of(parent.toString(), "/inheritance:r"));
        assertThat(OwnerOnly.holdsAcl(parent, acl, icacls.ntfs())).isTrue();
    }

    /** Files already inside (a graph from before) lose their own entries and inherit the user's. */
    @Test
    void the_entries_below_an_ntfs_directory_are_reset_to_inherit_it() throws IOException {
        Files.writeString(parent.resolve("neostore"), "x");
        Icacls icacls = new Icacls();

        OwnerOnly.restrictAcl(parent, new FakeAcl(USER, List.of()), icacls.ntfs());

        assertThat(icacls.calls)
                .containsExactly(
                        List.of(parent.toString(), "/inheritance:r"),
                        List.of(parent + "\\*", "/reset", "/T", "/C", "/Q"));
    }

    @Test
    void an_ntfs_directory_of_another_account_is_refused_untouched() {
        FakeAcl acl = new FakeAcl(OTHER, List.of(allow(EVERYONE, AclEntryPermission.READ_DATA)));
        Icacls icacls = new Icacls();

        assertThatThrownBy(() -> OwnerOnly.restrictAcl(parent, acl, icacls.ntfs()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("another account");
        assertThat(acl.getAcl()).containsExactly(allow(EVERYONE, AclEntryPermission.READ_DATA));
        assertThat(icacls.calls).isEmpty();
    }

    /** Fail closed: a failing icacls stops the start instead of leaving inheritance on. */
    @Test
    void a_failing_icacls_fails_the_restriction() {
        OwnerOnly.Ntfs failing =
                new OwnerOnly.Ntfs(
                        USER,
                        args -> {
                            throw new IOException("icacls failed with exit 5");
                        });

        assertThatThrownBy(
                        () -> OwnerOnly.restrictAcl(parent, new FakeAcl(USER, List.of()), failing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 5");
    }

    @Test
    void holds_is_false_for_an_inherited_entry_another_entry_or_another_owner() throws IOException {
        Icacls inherited = new Icacls();
        inherited.output = "C:\\data\\agentkb ayse:(OI)(CI)(F)\n BUILTIN\\Users:(I)(OI)(CI)(RX)\n";

        assertThat(
                        OwnerOnly.holdsAcl(
                                parent, new FakeAcl(USER, List.of(userOnly())), inherited.ntfs()))
                .as("an inherited entry: the parent still reaches in")
                .isFalse();
        assertThat(
                        OwnerOnly.holdsAcl(
                                parent,
                                new FakeAcl(
                                        USER,
                                        List.of(
                                                userOnly(),
                                                allow(EVERYONE, AclEntryPermission.READ_DATA))),
                                new Icacls().ntfs()))
                .isFalse();
        assertThat(
                        OwnerOnly.holdsAcl(
                                parent,
                                new FakeAcl(OTHER, List.of(userOnly())),
                                new Icacls().ntfs()))
                .isFalse();
    }
}
