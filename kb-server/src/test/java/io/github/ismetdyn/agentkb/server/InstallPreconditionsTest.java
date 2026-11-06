package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.InstallSandbox.Run;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * integrations/install.sh checks what it needs before it changes anything
 * (~/.claude/rules/general.md "Taşınabilirlik": a required tool is checked and a missing or too old
 * one is a clear error): java 21 or newer (CLAUDE.md "Teknoloji"), git, and the server jar it is to
 * install. A failed check leaves HOME empty. Sandbox: {@link InstallSandbox}.
 */
class InstallPreconditionsTest {

    @TempDir Path home;
    @TempDir Path tools;

    private InstallSandbox sandbox;

    @BeforeEach
    void sandbox() throws IOException {
        sandbox = new InstallSandbox(home, tools);
    }

    @ParameterizedTest(name = "java {0}")
    @ValueSource(ints = {21, 25})
    void java_21_or_newer_is_accepted(int version) throws IOException {
        sandbox.javaStub(version);

        Run run = sandbox.install();

        assertThat(run.exit()).as(run.stderr()).isZero();
    }

    @Test
    void java_older_than_21_stops_before_any_change_naming_the_version() throws IOException {
        sandbox.javaStub(17);

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains("21");
        assertThat(sandbox.tree()).isEmpty();
    }

    @Test
    void a_missing_java_stops_before_any_change() throws IOException {
        Files.delete(sandbox.stubBin.resolve("java"));

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains("java");
        assertThat(sandbox.tree()).isEmpty();
    }

    @Test
    void a_missing_git_stops_before_any_change() throws IOException {
        Files.delete(sandbox.stubBin.resolve("git"));

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains("git");
        assertThat(sandbox.tree()).isEmpty();
    }

    @Test
    void a_missing_server_jar_stops_before_any_change() throws IOException {
        Files.delete(sandbox.serverJar);

        Run run = sandbox.install();

        assertThat(run.exit()).isNotZero();
        assertThat(run.stderr()).contains(sandbox.serverJar.getFileName().toString());
        assertThat(sandbox.tree()).isEmpty();
    }
}
