package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * plan.md H7: kb --version prints the Maven project.version, which the build filters into a
 * resource (KbNativeIT checks the native binary prints the same). The expected version is read from
 * this module's pom.xml, so an unfiltered resource cannot pass.
 */
class KbVersionTest {

    @TempDir Path workDir;

    @Test
    void version_prints_the_maven_project_version() throws IOException {
        Matcher parent =
                Pattern.compile(
                                "<artifactId>agentkb-parent</artifactId>\\s*<version>([^<]+)</version>")
                        .matcher(Files.readString(Path.of("pom.xml")));
        assertThat(parent.find()).as("the parent version in pom.xml").isTrue();

        // No server is needed: the address is never called.
        Result result = KbProcess.run("http://127.0.0.1:9", "", workDir, "--version");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("kb " + parent.group(1) + "\n");
    }
}
