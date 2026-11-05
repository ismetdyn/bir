package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.KbRunTest.ID;
import static io.github.ismetdyn.agentkb.client.KbRunTest.VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Where {@code kb run --file} puts its private temporary file (docs/SECRETS.md §3). */
class KbRunTempDirTest {

    @TempDir Path workDir;

    private SecretStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new SecretStub().with(ID, VALUE);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /**
     * A private {@code $XDG_RUNTIME_DIR} (0700, the user's own) holds the {@code --file} file: it
     * is a tmpfs, so the value never reaches a disk.
     */
    @Test
    void a_file_goes_to_a_private_runtime_dir() throws IOException {
        Path runtimeDir = runtimeDir("rwx------");

        assertThat(fileDirUnder(runtimeDir)).isEqualTo(runtimeDir);
    }

    /** A runtime dir others can enter, or one that is missing, is not used; the JDK temp dir is. */
    @ParameterizedTest
    @ValueSource(strings = {"rwxr-xr-x", "missing"})
    void a_runtime_dir_that_is_not_private_is_not_used(String mode) throws IOException {
        Path runtimeDir = "missing".equals(mode) ? workDir.resolve("missing") : runtimeDir(mode);

        Path fileDir = fileDirUnder(runtimeDir);

        assertThat(fileDir).isNotEqualTo(runtimeDir);
        assertThat(fileDir).isDirectory();
    }

    private Path runtimeDir(String mode) throws IOException {
        return Files.createDirectory(
                workDir.resolve("runtime"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)));
    }

    /** The directory of the {@code --file} file when {@code XDG_RUNTIME_DIR} is {@code dir}. */
    private Path fileDirUnder(Path dir) {
        Result result =
                KbProcess.run(
                        server.url(),
                        Map.of("XDG_RUNTIME_DIR", dir.toString()),
                        "",
                        workDir,
                        KbProcess.runArgs(
                                List.of("--file", "KEY=kb:secret/" + ID), "echo \"$KEY\""));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return Path.of(result.stdout().strip()).getParent();
    }
}
