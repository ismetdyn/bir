package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * docs/SECRETS.md §5 "Veri dizini 0700": the graph holds secret values in plain text, so only the
 * owner may enter the data directory. A new one is created 0700; an existing wider one is tightened
 * to 0700 on start (decision: the server owns this directory, and refusing would stop every install
 * made before G8, whose directory was created with the umask).
 */
class DataDirPermissionTest {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    @TempDir Path parent;

    @Test
    void a_new_data_directory_is_created_owner_only() throws Exception {
        Path dataDir = parent.resolve("new").resolve("agentkb");

        startAndStop(dataDir);

        assertThat(Files.getPosixFilePermissions(dataDir)).isEqualTo(OWNER_ONLY);
    }

    @Test
    void an_existing_wider_data_directory_is_tightened() throws Exception {
        Path dataDir = Files.createDirectory(parent.resolve("agentkb"));
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwxr-xr-x"));

        startAndStop(dataDir);

        assertThat(Files.getPosixFilePermissions(dataDir)).isEqualTo(OWNER_ONLY);
    }

    private static void startAndStop(Path dataDir) {
        try (ConfigurableApplicationContext context =
                new SpringApplication(AgentkbServerApplication.class)
                        .run("--server.port=0", "--agentkb.data-dir=" + dataDir)) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
