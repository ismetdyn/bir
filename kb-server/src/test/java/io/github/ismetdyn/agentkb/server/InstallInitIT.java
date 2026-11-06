package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * integrations/install.sh with the real java and the server jar this build packaged (failsafe runs
 * after package): {@code init} creates the knowledge repository and the data directory under the
 * HOME install.sh was given, not under the password file's home, which the JVM would otherwise use
 * as user.home. The sandbox java refuses a {@code -jar} call without {@code -Duser.home=$HOME}, so
 * a regression fails here instead of touching this machine's real ~/kb.
 */
class InstallInitIT {

    private static final Path SERVER_JAR = Path.of("target", InstallSandbox.SERVER_JAR_NAME);
    private static final String GUARDED_JAVA =
            """
            #!/bin/sh
            for a in "$@"; do
              if [ "$a" = "-Duser.home=$HOME" ]; then exec '%1$s' "$@"; fi
            done
            for a in "$@"; do
              if [ "$a" = "-jar" ]; then
                echo "sandbox: java -jar without -Duser.home=$HOME refused" >&2
                exit 97
              fi
            done
            exec '%1$s' "$@"
            """;

    @TempDir Path home;
    @TempDir Path tools;

    @Test
    void init_creates_the_repository_and_the_data_directory_under_the_given_home()
            throws IOException {
        assertThat(SERVER_JAR).as("package builds the server jar first").isRegularFile();
        InstallSandbox sandbox = new InstallSandbox(home, tools);
        Path java = sandbox.stubBin.resolve("java");
        Files.writeString(
                java,
                GUARDED_JAVA.formatted(Path.of(System.getProperty("java.home"), "bin", "java")),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        Files.copy(SERVER_JAR, sandbox.serverJar, StandardCopyOption.REPLACE_EXISTING);

        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stdout() + run.stderr()).isZero();
        assertThat(home.resolve("kb/.git")).isDirectory();
        assertThat(home.resolve("kb/snapshot")).isDirectory();
        Path dataDir = home.resolve(".local/share/agentkb");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dataDir)))
                .isEqualTo("rwx------");
    }
}
