package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The jar this build packaged is what integrations/install.sh installs and its {@code kb} launcher
 * runs as {@code java -jar}; it must start on its own, with no class path from the build (failsafe
 * runs after package).
 */
class KbJarIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void the_packaged_jar_starts_with_java_dash_jar() throws Exception {
        Path jar = packagedJar();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process =
                new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--help")
                        .redirectErrorStream(true)
                        .start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("Usage: kb");
    }

    private static Path packagedJar() throws IOException {
        try (Stream<Path> target = Files.list(Path.of("target"))) {
            List<Path> jars =
                    target.filter(p -> p.getFileName().toString().matches("kb-client-.*\\.jar"))
                            .toList();
            assertThat(jars).as("package builds one client jar").hasSize(1);
            return jars.getFirst();
        }
    }
}
