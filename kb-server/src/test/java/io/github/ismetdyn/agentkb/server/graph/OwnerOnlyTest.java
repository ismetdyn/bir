package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0027 decision 2: the data directory holds secret values in plain text, so only the user
 * agentkb runs as may enter it. POSIX 0700 where the file system has POSIX permissions. On NTFS an
 * ACL with that user as its only entry, inheritance from the parent cut and the entries below reset
 * (icacls), refused when the directory belongs to another account. Refused where the file system
 * has neither. The ACL branch runs on Linux through a fake view and a recording icacls; the real
 * one runs only on Windows.
 */
class OwnerOnlyTest {

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
            assertThatThrownBy(() -> OwnerOnly.directory(dataDir))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("neither POSIX permissions nor ACLs");
            assertThatThrownBy(() -> new EmbeddedNeo4jStore(dataDir))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("owner-only")
                    .cause()
                    .hasMessageContaining("neither POSIX permissions nor ACLs");
        }
    }

    /** The icacls runner fails closed: a non-zero exit or an overrun is an error, not a pass. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "drives /bin/sh in place of icacls.exe")
    void the_icacls_runner_returns_output_and_fails_on_exit_or_timeout() throws IOException {
        Path sh = Path.of("/bin/sh");
        Duration timeout = Duration.ofSeconds(5);

        assertThat(OwnerOnly.Ntfs.run(sh, List.of("-c", "echo ok"), timeout)).isEqualTo("ok\n");
        assertThatThrownBy(() -> OwnerOnly.Ntfs.run(sh, List.of("-c", "exit 3"), timeout))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 3");
        long start = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                OwnerOnly.Ntfs.run(
                                        sh, List.of("-c", "sleep 30"), Duration.ofMillis(300)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish");
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(10));
    }

    /** ADR-0027 decision 2 on NTFS: run on the Windows test machine (W0). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_the_directory_and_what_is_created_in_it_are_the_users_only()
            throws IOException {
        Path dir = parent.resolve("agentkb");

        OwnerOnly.directory(dir);
        Path child = Files.writeString(dir.resolve("child.txt"), "x");

        UserPrincipal owner = Files.getOwner(dir);
        assertThat(OwnerOnly.holds(dir)).isTrue();
        assertThat(Files.getFileAttributeView(child, AclFileAttributeView.class).getAcl())
                .extracting(AclEntry::principal)
                .containsOnly(owner);
    }

    /**
     * On Windows: an entry granted on the parent afterwards, inheritable and propagated, does not
     * reach the data directory; one granted before is cut, also from a file already inside.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void on_windows_a_grant_on_the_parent_does_not_reach_in() throws Exception {
        Path dir = Files.createDirectory(parent.resolve("agentkb"));
        Path old = Files.writeString(dir.resolve("old.txt"), "x");
        // *S-1-1-0 is Everyone in any language.
        icacls(parent, "/grant", "*S-1-1-0:(OI)(CI)(RX)");

        OwnerOnly.directory(dir);
        icacls(parent, "/grant", "*S-1-1-0:(OI)(CI)(M)");

        UserPrincipal owner = Files.getOwner(dir);
        assertThat(OwnerOnly.holds(dir)).isTrue();
        assertThat(Files.getFileAttributeView(old, AclFileAttributeView.class).getAcl())
                .extracting(AclEntry::principal)
                .containsOnly(owner);
        assertThat(icacls(dir)).doesNotContain("(I)S-1-1-0").doesNotContain("Everyone");
    }

    private static String icacls(Path path, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(path.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
