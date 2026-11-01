package io.github.ismetdyn.agentkb.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real application on a real socket. Only the port and the data directory differ from production, so
 * {@code server.address} still comes from application.yml. Subclasses share one cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class RunningServerTestBase {

    // ponytail: lives under target/ because the cached context outlives any test class; `clean` removes it.
    private static final Path DATA_DIR = createDataDir();

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void useTemporaryDataDir(DynamicPropertyRegistry registry) {
        registry.add("agentkb.data-dir", DATA_DIR::toString);
    }

    private static Path createDataDir() {
        try {
            Path target = Files.createDirectories(Path.of("target"));
            return Files.createTempDirectory(target, "agentkb-test-").toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
