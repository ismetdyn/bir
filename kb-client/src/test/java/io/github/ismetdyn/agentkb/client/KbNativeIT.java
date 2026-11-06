package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The native {@code kb} (plan.md H1, ADR-0025 decision 7): {@link KbTerminalScenarios} against the
 * binary, plus its size, its start time and the JSON commands answering exactly as on the JVM. The
 * binary is {@code target/kb} ({@code -Dkb.native=<path>} overrides), built by {@code mise exec
 * java@mandrel-23.1.13.0-Final+java21 -- ./mvnw -pl kb-client -am -Pnative package}. A missing
 * binary, or one older than the compiled classes, fails every test when the binary was asked for
 * ({@code -Dkb.native}, which the native profile sets), and otherwise aborts it as "not run", never
 * as a pass.
 */
class KbNativeIT extends KbTerminalScenarios {

    private static final String BINARY_PROPERTY = "kb.native";
    private static final boolean BINARY_REQUIRED = System.getProperty(BINARY_PROPERTY) != null;
    private static final Path BINARY = Path.of(System.getProperty(BINARY_PROPERTY, "target/kb"));
    private static final Path CLASSES = Path.of("target/classes");

    /** plan.md H1. */
    private static final long MAX_BYTES = 50_000_000L;

    private static final int START_RUNS = 7;

    /** plan.md H1's target; reported, not enforced, since a busy machine can miss it. */
    private static final long TARGET_MEDIAN_START_MILLIS = 50;

    /**
     * The enforced bound: generous so load on the machine cannot fail the build, still far below a
     * broken image ({@code kb --help} on the JVM took 120 ms on an idle development machine,
     * 2026-11-06, and several times that under load).
     */
    private static final long MAX_MEDIAN_START_MILLIS = 200;

    private static final String BATCH = "01KBTESTBATCH0000000000001";

    /**
     * Per test, so the report counts every test as failed or skipped and gives the reason. Under
     * the native profile a missing or stale binary is a failure: the build asked for it.
     */
    @BeforeEach
    void requireBinary() throws IOException {
        Optional<String> reason = missingBinary();
        if (reason.isEmpty()) {
            return;
        }
        if (BINARY_REQUIRED) {
            throw new AssertionError(
                    "KbNativeIT failed: "
                            + BINARY_PROPERTY
                            + " is set and "
                            + reason.orElseThrow());
        }
        System.err.println("KbNativeIT not run: " + reason.orElseThrow());
        Assumptions.abort("not run: " + reason.orElseThrow());
    }

    private static Optional<String> missingBinary() throws IOException {
        if (!Files.isExecutable(BINARY)) {
            return Optional.of(
                    "there is no native kb at "
                            + BINARY.toAbsolutePath()
                            + "; build it with: mise exec java@mandrel-23.1.13.0-Final+java21 --"
                            + " ./mvnw -pl kb-client -am -Pnative package");
        }
        if (Files.getLastModifiedTime(BINARY).compareTo(newestClass()) < 0) {
            return Optional.of(BINARY + " is older than target/classes; rebuild it");
        }
        return Optional.empty();
    }

    private static FileTime newestClass() throws IOException {
        try (Stream<Path> files = Files.walk(CLASSES)) {
            return files.filter(Files::isRegularFile)
                    .map(KbNativeIT::modified)
                    .max(FileTime::compareTo)
                    .orElse(FileTime.fromMillis(0));
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    List<String> launcher() {
        return List.of(BINARY.toAbsolutePath().toString());
    }

    @Test
    void the_binary_is_smaller_than_50_mb() throws IOException {
        assertThat(Files.size(BINARY)).isLessThan(MAX_BYTES);
    }

    @Test
    void help_starts_with_a_median_below_200_ms() throws IOException {
        long[] millis = new long[START_RUNS];
        for (int run = 0; run < START_RUNS; run++) {
            long start = System.nanoTime();
            Result result =
                    KbProcess.run(
                            launcher(), "http://127.0.0.1:9", Map.of(), "", workDir, "--help");
            millis[run] = (System.nanoTime() - start) / 1_000_000;
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("Usage: kb");
        }
        long median = LongStream.of(millis).sorted().toArray()[START_RUNS / 2];
        // The measurement plan.md H1 asks to write down; failsafe keeps it in the report.
        System.out.printf(
                "native kb --help: median %d ms of %d runs (target %d ms, fails above %d ms),"
                        + " size %d bytes%n",
                median,
                START_RUNS,
                TARGET_MEDIAN_START_MILLIS,
                MAX_MEDIAN_START_MILLIS,
                Files.size(BINARY));
        assertThat(median).isLessThan(MAX_MEDIAN_START_MILLIS);
    }

    /**
     * The JSON commands on the wire (plan.md H1: a request body goes out and an answer, a refusal
     * included, comes back in native whatever maps it): the binary prints, exits and sends exactly
     * what the JVM does. An unknown answer field is ignored on both.
     */
    @ParameterizedTest
    @MethodSource("jsonExchanges")
    void a_json_command_answers_as_on_the_jvm(
            String method, String path, int status, String answer, int exitCode, String[] args)
            throws IOException {
        Result onJvm = exchange(KbProcess.jvm(Map.of()), method, path, status, answer, args);
        Result onNative = exchange(launcher(), method, path, status, answer, args);

        assertThat(onJvm.exitCode()).as(onJvm.stderr()).isEqualTo(exitCode);
        assertThat(onNative).isEqualTo(onJvm);
    }

    static Stream<Arguments> jsonExchanges() {
        return Stream.of(
                arguments(
                        "POST",
                        "/api/v1/revert",
                        200,
                        "{\"batch\":\""
                                + BATCH
                                + "\",\"deleted\":17,\"reopened\":4,"
                                + "\"deletedSecretValues\":2,\"later\":\"ignored\"}",
                        0,
                        new String[] {"revert", "--batch", BATCH}),
                arguments(
                        "POST",
                        "/api/v1/revert",
                        400,
                        "{\"error\":\"a later batch built on this one\","
                                + "\"hint\":\"Revert batch 01KBTESTLATER first.\"}",
                        1,
                        new String[] {"revert", "--batch", BATCH}),
                arguments(
                        "POST",
                        "/api/v1/restore",
                        200,
                        "{\"nodes\":45,\"edges\":57,\"keptSecrets\":2,\"keptSignals\":3,"
                                + "\"droppedEdges\":0,\"droppedSecretValues\":1}",
                        0,
                        new String[] {"restore", "--at", "HEAD~1"}),
                arguments(
                        "POST",
                        "/api/v1/sync",
                        200,
                        "{\"commit\":\"0123abc\",\"pushed\":true,\"nodes\":45,\"edges\":57,"
                                + "\"conflicts\":1}",
                        0,
                        new String[] {"sync"}),
                arguments(
                        "GET",
                        "/api/v1/check",
                        200,
                        "{\"clean\":false,\"problems\":[{\"kind\":\"dangling-edge\","
                                + "\"id\":\"e1\",\"detail\":\"no target ş\"}]}",
                        1,
                        new String[] {"check"}));
    }

    /** kb's output and what it sent, so two runs can be compared as one value. */
    private Result exchange(
            List<String> launcher,
            String method,
            String path,
            int status,
            String answer,
            String[] args)
            throws IOException {
        try (ApiStub stub = new ApiStub().answer(method, path, status, answer)) {
            Result result = KbProcess.run(launcher, stub.url(), Map.of(), "", workDir, args);
            List<String> sent = new ArrayList<>();
            stub.requests().forEach(r -> sent.add(r.method() + " " + r.rawPath() + " " + r.body()));
            return new Result(result.exitCode(), result.stdout() + sent, result.stderr());
        }
    }
}
