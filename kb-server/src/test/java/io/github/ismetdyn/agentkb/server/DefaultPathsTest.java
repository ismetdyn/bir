package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * ARCHITECTURE §14: in production {@code agentkb.repo} defaults to {@code ~/kb}, so the systemd
 * unit needs no argument. The defaults come from AgentkbPaths alone (ADR-0027 decision 1; its
 * values are checked in AgentkbPathsTest), never from a second copy in application.yml. Tests must
 * never reach the real {@code ~/kb} or the real data directory: a server started there would import
 * the user's snapshot and commit its own on shutdown (ShutdownSnapshotTest). So inside the test JVM
 * a server started without the two properties resolves them to blank or to somewhere other than the
 * real home's paths (todo G4 LOW item).
 */
class DefaultPathsTest {

    private static final Path MAIN_CONFIG = Path.of("src", "main", "resources", "application.yml");

    @Test
    void application_yml_holds_no_second_copy_of_the_default_paths() throws IOException {
        JsonNode config = new YAMLMapper().readTree(MAIN_CONFIG.toFile());

        assertThat(config.at("/agentkb/repo").isMissingNode()).isTrue();
        assertThat(config.at("/agentkb/data-dir").isMissingNode()).isTrue();
        assertThat(config.at("/agentkb/scan/allowed-roots").isMissingNode()).isTrue();
    }

    @Test
    void the_server_takes_its_default_paths_from_agentkb_paths() {
        Path home = Path.of("/nonexistent-home");
        AgentkbPaths paths = AgentkbPaths.resolve("Linux", Map.of(), home, path -> false);

        // Default properties are the last source: everything else overrides them.
        PropertySource<?> defaults =
                prepared(AgentkbServerApplication.application(paths)).getPropertySources().stream()
                        .reduce((first, second) -> second)
                        .orElseThrow();

        assertThat(defaults.getProperty("agentkb.repo")).isEqualTo(home.resolve("kb").toString());
        assertThat(defaults.getProperty("agentkb.data-dir"))
                .isEqualTo(home.resolve(".local/share/agentkb").toString());
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

    /** The two properties as a server started by main with no arguments would see them. */
    private static Map<String, String> resolvedWithoutArguments() {
        // As main builds it, with this machine's real defaults underneath.
        ConfigurableEnvironment environment =
                prepared(AgentkbServerApplication.application(AgentkbPaths.ofThisMachine()));
        Map<String, String> resolved = new HashMap<>();
        resolved.put("agentkb.repo", environment.getProperty("agentkb.repo"));
        resolved.put("agentkb.data-dir", environment.getProperty("agentkb.data-dir"));
        return resolved;
    }

    /**
     * The environment once it is ready; the start is then aborted, so no bean, no Neo4j and no repo
     * is touched.
     */
    private static ConfigurableEnvironment prepared(SpringApplication application) {
        AtomicReference<ConfigurableEnvironment> environment = new AtomicReference<>();
        application.addListeners(
                (ApplicationListener<ApplicationEnvironmentPreparedEvent>)
                        event -> {
                            environment.set(event.getEnvironment());
                            throw new StopBeforeContext();
                        });

        Throwable stopped = catchThrowable(() -> application.run("--server.port=0"));

        assertThat(stopped).isInstanceOf(StopBeforeContext.class);
        return environment.get();
    }

    private static final class StopBeforeContext extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopBeforeContext() {
            super("stopped once the environment was read", null, false, false);
        }
    }
}
