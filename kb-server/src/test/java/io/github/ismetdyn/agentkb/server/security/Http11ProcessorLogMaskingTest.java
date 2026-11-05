package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariants 2 and 10: Tomcat quotes bytes it could not parse as a request. It logs that at INFO
 * once per JVM and at DEBUG afterwards, so in the shared test JVM the outcome would depend on which
 * test came first. Each test here therefore owns a JVM, and sends the one request that logs in it.
 */
class Http11ProcessorLogMaskingTest {

    private static final String SENTINEL = "sentinel-http11-log-3fb9";
    private static final String LOGGER = "Http11Processor";
    private static final Pattern MASKED_LINE = Pattern.compile(LOGGER + "\\s*: \\*{5}$");

    @TempDir private Path workDir;

    @Test
    void host_the_parser_refuses_is_logged_masked() throws Exception {
        try (ChildServer server = ChildServer.start(workDir)) {
            String hosts =
                    "Host: 127.0.0.1:" + server.port + ", " + SENTINEL + ".example:" + server.port;

            RawHttp.Reply reply =
                    RawHttp.send(
                            server.port,
                            RawHttp.HTTP_1_1,
                            "POST",
                            SecurityTestBase.AGENT,
                            "{}",
                            hosts,
                            SecurityTestBase.CONTENT_TYPE);

            assertThat(reply.status()).isEqualTo(400);
            server.awaitOutput(LOGGER);
        }
        assertMaskedEventOnly();
    }

    /**
     * The first request has to succeed: a failed one closes the connection before the remainder is
     * parsed.
     */
    @Test
    void body_running_past_a_short_content_length_is_logged_masked() throws Exception {
        String body = SecurityTestBase.INITIALIZE;
        try (ChildServer server = ChildServer.start(workDir)) {
            String request =
                    "POST "
                            + SecurityTestBase.AGENT
                            + " HTTP/1.1\r\nHost: 127.0.0.1:"
                            + server.port
                            + "\r\n"
                            + SecurityTestBase.CONTENT_TYPE
                            + "\r\n"
                            + SecurityTestBase.ACCEPT
                            + "\r\nContent-Length: "
                            + body.length()
                            + "\r\n\r\n"
                            + body
                            + "{\"secret\":\""
                            + SENTINEL
                            + "\"}";

            String replies = RawHttp.exchange(server.port, request, request.length());

            assertThat(replies).contains("HTTP/1.1 200 ").contains("HTTP/1.1 400 ");
            server.awaitOutput(LOGGER);
        }
        assertMaskedEventOnly();
    }

    /**
     * Reads after the child has exited, so nothing it logs while handling or shutting down can
     * escape the check.
     */
    private void assertMaskedEventOnly() throws IOException {
        String output = Files.readString(ChildServer.outputIn(workDir));
        List<String> lines = output.lines().filter(line -> line.contains(LOGGER)).toList();

        assertThat(output)
                .doesNotContain(SENTINEL)
                .doesNotContain("is not valid")
                .doesNotContain("Invalid character");
        assertThat(lines).as("the event stays visible in: %s", output).isNotEmpty();
        assertThat(lines)
                .allSatisfy(
                        line ->
                                assertThat(MASKED_LINE.matcher(line.strip()).find())
                                        .as(line)
                                        .isTrue());
    }

    /**
     * The application in its own JVM on the test classpath, so it runs the real logback-spring.xml.
     */
    private static final class ChildServer implements AutoCloseable {

        private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
        private static final Duration LOG_TIMEOUT = Duration.ofSeconds(20);
        private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
        private static final int STOP_TIMEOUT_SECONDS = 30;
        private static final Pattern STARTED = Pattern.compile("Tomcat started on port (\\d+)");

        private final Process process;
        private final Path output;
        private final int port;

        private ChildServer(Process process, Path output) throws IOException, InterruptedException {
            this.process = process;
            this.output = output;
            this.port = Integer.parseInt(await(STARTED, START_TIMEOUT).group(1));
        }

        static Path outputIn(Path workDir) {
            return workDir.resolve("child-output.log");
        }

        static ChildServer start(Path workDir) throws IOException, InterruptedException {
            Path output = outputIn(workDir);
            Process process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    AgentkbServerApplication.class.getName(),
                                    "--server.port=0",
                                    "--agentkb.data-dir=" + workDir.resolve("data"))
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                return new ChildServer(process, output);
            } catch (IOException | InterruptedException | AssertionError e) {
                stop(process);
                throw e;
            }
        }

        void awaitOutput(String text) throws IOException, InterruptedException {
            await(Pattern.compile(Pattern.quote(text)), LOG_TIMEOUT);
        }

        private Matcher await(Pattern pattern, Duration timeout)
                throws IOException, InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline) && process.isAlive()) {
                Matcher matcher = pattern.matcher(Files.readString(output));
                if (matcher.find()) {
                    return matcher;
                }
                Thread.sleep(POLL_INTERVAL);
            }
            return fail(
                    "no match for %s, child alive: %s, output: %s",
                    pattern, process.isAlive(), Files.readString(output));
        }

        @Override
        public void close() throws InterruptedException {
            stop(process);
        }

        private static void stop(Process process) throws InterruptedException {
            process.destroy();
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
