package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Invariants 2 and 10, for request bytes that reach a log without passing through an MCP transport
 * logger: Tomcat quotes an invalid Cookie header, and an exception escaping the DispatcherServlet
 * quotes an invalid Accept header.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContainerLogMaskingTest extends SecurityTestBase {

    private static final String SENTINEL = "sentinel-container-log-7ad2";
    private static final String FORM_CONTENT_TYPE =
            "Content-Type: application/x-www-form-urlencoded";
    private static final String MASKED_ENDING = " : *****";
    // Ends of the logger names; the masked pattern cuts the start of a long logger name.
    private static final String COOKIE_LOGGER = "parser.Cookie";
    private static final String DISPATCHER_SERVLET_LOGGER = "[dispatcherServlet]";
    private static final String TRANSPORT_LOGGER = "WebMvcStreamableServerTransportProvider";
    // What is left of ...core.ContainerBase.[Tomcat].[localhost].[/].[<servlet>] after the pattern
    // abbreviates it.
    private static final String CONTAINER_LOGGER = "o.a.c.c.C.[";

    private int capturedBeforeTest;

    @BeforeEach
    void markCapturedOutput(CapturedOutput output) {
        capturedBeforeTest = output.length();
    }

    /**
     * Tomcat logs an invalid cookie at INFO once per JVM, so this is the only test sending one; a
     * second sender would empty the capture, which the visibility check reports as a failure rather
     * than a false green.
     */
    @Test
    void invalid_cookie_is_logged_masked(CapturedOutput output) throws IOException {
        postJson(AGENT, "{}", host(), "Cookie: a=b c " + SENTINEL);

        assertMaskedEvent(COOKIE_LOGGER, output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void invalid_accept_header_is_logged_masked(String method, CapturedOutput output)
            throws IOException {
        RawHttp.send(
                port, HTTP_1_1, method, AGENT, "{}", host(), CONTENT_TYPE, "Accept: " + SENTINEL);

        assertMaskedEvent(DISPATCHER_SERVLET_LOGGER, output);
    }

    /**
     * Tomcat 11 throws on these instead of logging them, and the exception text ends up at the
     * transport logger.
     */
    @ParameterizedTest
    @ValueSource(strings = {"=" + SENTINEL, "a=" + SENTINEL + "%ZZ"})
    void invalid_form_body_is_logged_masked(String body, CapturedOutput output) throws IOException {
        RawHttp.send(port, HTTP_1_1, "POST", AGENT, body, host(), FORM_CONTENT_TYPE, ACCEPT);

        assertMaskedEvent(TRANSPORT_LOGGER, output);
    }

    /**
     * The curator servlet is not the DispatcherServlet: what escapes it reaches a container logger
     * of its own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void invalid_accept_header_on_curator_leaves_no_unmasked_line(
            String method, CapturedOutput output) throws IOException {
        Reply reply =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        method,
                        CURATOR,
                        "{}",
                        host(),
                        CONTENT_TYPE,
                        "Accept: " + SENTINEL);

        assertNoUnmaskedLine(reply, output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=" + SENTINEL, "a=" + SENTINEL + "%ZZ"})
    void invalid_form_body_on_curator_leaves_no_unmasked_line(String body, CapturedOutput output)
            throws IOException {
        Reply reply =
                RawHttp.send(
                        port, HTTP_1_1, "POST", CURATOR, body, host(), FORM_CONTENT_TYPE, ACCEPT);

        assertNoUnmaskedLine(reply, output);
    }

    /**
     * Spring's FormContentFilter decodes PUT forms ahead of either servlet: its failure is outside
     * both transports.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void invalid_put_form_body_leaves_no_unmasked_line(String endpoint, CapturedOutput output)
            throws IOException {
        String body = "a=" + SENTINEL + "%ZZ";

        Reply reply =
                RawHttp.send(
                        port, HTTP_1_1, "PUT", endpoint, body, host(), FORM_CONTENT_TYPE, ACCEPT);

        assertNoUnmaskedLine(reply, output);
    }

    /**
     * A 403 would mean the request never got past the guard, and an empty log would then prove
     * nothing.
     */
    private void assertNoUnmaskedLine(Reply reply, CapturedOutput output) {
        String captured = output.getAll().substring(capturedBeforeTest);

        assertThat(reply.status()).as(reply.body()).isNotEqualTo(403);
        assertThat(captured)
                .doesNotContain(SENTINEL)
                .doesNotContain("Exception")
                .doesNotContain("ZZ");
        assertThat(captured.lines().filter(line -> line.contains(CONTAINER_LOGGER)))
                .allSatisfy(line -> assertThat(line.strip()).endsWith(MASKED_ENDING));
    }

    private void assertMaskedEvent(String logger, CapturedOutput output) {
        String captured = output.getAll().substring(capturedBeforeTest);
        List<String> lines = captured.lines().filter(line -> line.contains(logger)).toList();

        assertThat(lines).as("the event stays visible in: %s", captured).isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line.strip()).endsWith(MASKED_ENDING));
        assertThat(captured).doesNotContain(SENTINEL).doesNotContain("Exception");
    }
}
