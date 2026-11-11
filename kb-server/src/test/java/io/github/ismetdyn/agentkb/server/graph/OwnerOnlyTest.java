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
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
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
 * has neither. The NTFS rules through fakes are OwnerOnlyNtfsTest's; here the real file systems:
 * POSIX on Linux, NTFS only on Windows.
 */
class OwnerOnlyTest {

    @TempDir Path parent;

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
