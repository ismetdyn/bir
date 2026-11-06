package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * ARCHITECTURE §14: in production {@code agentkb.repo} defaults to {@code ~/kb}, so the systemd
 * unit needs no argument. Tests must never reach the real {@code ~/kb} or the real data directory:
 * a server started there would import the user's snapshot and commit its own on shutdown
 * (ShutdownSnapshotTest). So inside the test JVM a server started without the two properties
 * resolves them to blank or to somewhere other than the real home's paths (todo G4 LOW item).
 */
class DefaultPathsTest {

    private static final Path MAIN_CONFIG = Path.of("src", "main", "resources", "application.yml");

    @Test
    void the_production_default_repo_is_kb_in_the_home_directory() throws IOException {
        JsonNode config = new YAMLMapper().readTree(MAIN_CONFIG.toFile());

        assertThat(config.at("/agentkb/repo").asString("")).matches("\\$\\{(user\\.home|HOME)}/kb");
    }

    @Test
    void a_server_started_in_tests_without_paths_never_resolves_the_real_home() {
        Map<String, String> resolved = resolvedWithoutArguments();
        Path realHome = Path.of(System.getenv("HOME")).toAbsolutePath().normalize();

        assertThat(resolved.get("agentkb.repo"))
                .satisfiesAnyOf(
                        repo -> assertThat(repo).isNullOrEmpty(),
                        repo ->
                                assertThat(
                                                Path.of(repo)
                                                        .toAbsolutePath()
                                                        .normalize()
                                                        .startsWith(realHome.resolve("kb")))
                                        .as("%s is the real ~/kb", repo)
                                        .isFalse());
        assertThat(resolved.get("agentkb.data-dir"))
                .satisfiesAnyOf(
                        dataDir -> assertThat(dataDir).isNullOrEmpty(),
                        dataDir ->
                                assertThat(
                                                Path.of(dataDir)
                                                        .toAbsolutePath()
                                                        .normalize()
                                                        .startsWith(realHome.resolve(".local")))
                                        .as("%s is under the real ~/.local", dataDir)
                                        .isFalse());
    }

    /**
     * The two properties as a server started with no arguments would see them, read when the
     * environment is ready; the start is then aborted, so no bean, no Neo4j and no repo is touched.
     */
    private static Map<String, String> resolvedWithoutArguments() {
        Map<String, String> resolved = new HashMap<>();
        SpringApplication application = new SpringApplication(AgentkbServerApplication.class);
        application.addListeners(
                (ApplicationListener<ApplicationEnvironmentPreparedEvent>)
                        event -> {
                            resolved.put(
                                    "agentkb.repo",
                                    event.getEnvironment().getProperty("agentkb.repo"));
                            resolved.put(
                                    "agentkb.data-dir",
                                    event.getEnvironment().getProperty("agentkb.data-dir"));
                            throw new StopBeforeContext();
                        });

        Throwable stopped = catchThrowable(() -> application.run("--server.port=0"));

        assertThat(stopped).isInstanceOf(StopBeforeContext.class);
        return resolved;
    }

    private static final class StopBeforeContext extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopBeforeContext() {
            super("stopped once the environment was read", null, false, false);
        }
    }
}
