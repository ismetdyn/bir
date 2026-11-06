package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs {@code kb} the way a user or an agent does: its own JVM, a real exit code, real stdout and
 * stderr, the server address from {@code AGENTKB_URL}, and stdin a pipe, so it is not a terminal.
 * Black-box on purpose: what is checked is the process, not a class.
 */
final class KbProcess {

    /** The kb entry point; the only name of the client implementation the tests know. */
    static final String MAIN_CLASS = "io.github.ismetdyn.agentkb.client.Kb";

    private static final long TIMEOUT_SECONDS = 60;

    record Result(int exitCode, String stdout, String stderr) {}

    private KbProcess() {}

    static Result run(String serverUrl, String stdin, Path workDir, String... args) {
        return run(serverUrl, Map.of(), stdin, workDir, args);
    }

    /** As {@link #run(String, String, Path, String...)} with extra environment variables. */
    static Result run(
            String serverUrl, Map<String, String> env, String stdin, Path workDir, String... args) {
        try {
            Process process = start(serverUrl, env, workDir, args);
            CompletableFuture<String> out = drain(process.getInputStream());
            CompletableFuture<String> err = drain(process.getErrorStream());
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("kb did not exit within " + TIMEOUT_SECONDS + " s");
            }
            return new Result(process.exitValue(), out.join(), err.join());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /**
     * Starts kb and leaves it running, for a test that signals it; the caller drains or ignores.
     */
    static Process start(String serverUrl, Path workDir, String... args) throws IOException {
        return start(serverUrl, Map.of(), workDir, args);
    }

    private static Process start(
            String serverUrl, Map<String, String> env, Path workDir, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // A test HOME is the JVM's home too: on Linux user.home comes from the password file, not
        // from $HOME, so kb would otherwise read this machine's real home.
        if (env.containsKey("HOME")) {
            command.add("-Duser.home=" + env.get("HOME"));
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MAIN_CLASS);
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.environment().put("AGENTKB_URL", serverUrl);
        // The POSIX locale, as under cron or a bare systemd unit: kb must pass bytes through and
        // print values as UTF-8 whatever the terminal encoding says.
        builder.environment().put("LC_ALL", "C");
        builder.environment().putAll(env);
        return builder.start();
    }

    /** {@code run <options> -- sh -c <script> sh <scriptArgs>}. */
    static String[] runArgs(List<String> options, String script, String... scriptArgs) {
        return Stream.of(
                        Stream.of("run"),
                        options.stream(),
                        Stream.of("--", "sh", "-c", script, "sh"),
                        Stream.of(scriptArgs))
                .flatMap(s -> s)
                .toArray(String[]::new);
    }

    /** Whether a child ran {@code touch ran} in {@code workDir}. */
    static boolean ran(Path workDir) {
        return Files.exists(workDir.resolve("ran"));
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (stream) {
                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
