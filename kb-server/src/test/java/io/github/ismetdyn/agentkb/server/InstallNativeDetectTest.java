package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How integrations/install.sh picks the kb client (README "Kurulum"): with {@code
 * AGENTKB_CLIENT_NATIVE} unset it takes its checkout's {@code kb-client/target/kb} when that is an
 * executable file, and warns, without failing, when a source under kb-core, kb-api or kb-client is
 * newer than it; a non-executable build there means the jar. A given path that is not executable is
 * an error. The script runs from a copy of this checkout's integrations/ so the checkout's own
 * target/ never takes part. Sandbox: {@link InstallSandbox}.
 */
class InstallNativeDetectTest {

    private static final Instant BUILT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path home;
    @TempDir Path tools;

    private InstallSandbox sandbox;
    private Path checkout;
    private Path builtKb;

    @BeforeEach
    void sandbox() throws IOException {
        sandbox = new InstallSandbox(home, tools);
        checkout = Files.createDirectories(tools.resolve("checkout"));
        Path integrations = checkout.resolve("integrations");
        try (Stream<Path> walk = Files.walk(InstallSandbox.INTEGRATIONS)) {
            for (Path source : walk.toList()) {
                Files.copy(
                        source,
                        integrations.resolve(InstallSandbox.INTEGRATIONS.relativize(source)),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        builtKb = Files.createDirectories(checkout.resolve("kb-client/target")).resolve("kb");
        Files.copy(sandbox.nativeKb, builtKb, StandardCopyOption.COPY_ATTRIBUTES);
        Files.setLastModifiedTime(builtKb, FileTime.from(BUILT));
        sandbox.script = integrations.resolve("install.sh");
        sandbox.clientNative = null;
    }

    @Test
    void with_the_variable_unset_the_checkouts_native_build_is_installed() throws IOException {
        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(home.resolve("kb-tools/kb")).hasSameBinaryContentAs(builtKb);
        assertThat(home.resolve("kb-tools/agentkb-client.jar")).doesNotExist();
        assertThat(run.stderr()).doesNotContain("WARN: native kb");
    }

    @Test
    void a_source_newer_than_the_native_build_is_named_in_a_warning() throws IOException {
        Path source = source("kb-api", BUILT.plus(Duration.ofHours(1)));

        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.stdout()).contains("kb client: native");
        assertThat(run.stderr()).contains("WARN: native kb", builtKb.toString(), source.toString());
    }

    @Test
    void sources_older_than_the_native_build_give_no_warning() throws IOException {
        for (String module : new String[] {"kb-core", "kb-api", "kb-client"}) {
            source(module, BUILT.minus(Duration.ofHours(1)));
        }

        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.stderr()).doesNotContain("WARN: native kb");
    }

    @Test
    void a_non_executable_native_build_in_the_checkout_means_the_jar() throws IOException {
        Files.setPosixFilePermissions(builtKb, PosixFilePermissions.fromString("rw-------"));

        Run run = sandbox.install("--dry-run");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.stdout()).contains("kb client: jar");
    }

    @Test
    void a_given_native_kb_that_is_not_executable_stops_before_any_change() throws IOException {
        Files.setPosixFilePermissions(builtKb, PosixFilePermissions.fromString("rw-------"));
        sandbox.clientNative = builtKb.toString();

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains("not executable", builtKb.toString());
        assertThat(sandbox.tree()).isEmpty();
    }

    private Path source(String module, Instant modified) throws IOException {
        Path source =
                Files.createDirectories(checkout.resolve(module).resolve("src/main/java"))
                        .resolve("Source.java");
        Files.writeString(source, "class Source {}\n");
        Files.setLastModifiedTime(source, FileTime.from(modified));
        return source;
    }
}
