package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs kb on a real terminal (src/test/resources/tty_drive.py, a pseudo-terminal from Python's
 * {@code pty}; the JDK cannot open one): waits for a text on the screen, types keys, and reports
 * what kb wrote and how it ended. A missing {@code python3} aborts the test as "not run", never as
 * a pass.
 */
final class TtyDriver {

    /** A terminal type with an alternate screen, so a TUI shows whether it uses it. */
    static final String TERM = "xterm-256color";

    private static final long GRACE_SECONDS = 30;

    /**
     * {@code status}: {@code exit=<code>}, {@code timeout:screen}, {@code timeout:exit} or {@code
     * ended-before-keys exit=<code>}. {@code screen}: the raw bytes kb wrote, as UTF-8.
     */
    record Session(String status, String screen) {}

    private TtyDriver() {}

    static Session drive(
            List<String> kbCommand,
            Map<String, String> env,
            Path workDir,
            String waitFor,
            String keys,
            int screenSeconds,
            int exitSeconds) {
        Assumptions.assumeTrue(
                hasPython(), "not run: python3 is needed to give kb a terminal (tty_drive.py)");
        List<String> command = new ArrayList<>();
        command.addAll(
                List.of(
                        "python3",
                        "-I",
                        script().toString(),
                        waitFor,
                        keys,
                        Integer.toString(screenSeconds),
                        Integer.toString(exitSeconds),
                        "--"));
        command.addAll(kbCommand);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.environment().put("TERM", TERM);
        builder.environment().putAll(env);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            var err = process.getErrorStream();
            byte[] screen = process.getInputStream().readAllBytes();
            String status = new String(err.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(screenSeconds + exitSeconds + GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("tty_drive.py did not end");
            }
            String last = status.lines().reduce((first, second) -> second).orElse("");
            return new Session(last, new String(screen, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static boolean hasPython() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path script() {
        try {
            return Path.of(TtyDriver.class.getResource("/tty_drive.py").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
