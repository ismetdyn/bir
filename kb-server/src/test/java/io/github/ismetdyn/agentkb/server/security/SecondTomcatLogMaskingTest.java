package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import org.apache.catalina.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Invariants 2 and 10 do not depend on start order. Spring Boot names the Tomcat engine of every
 * web server after the first one in a JVM {@code Tomcat-<n>}, so the container loggers of that
 * server are not the ones ContainerLogMaskingTest sees under the first engine name. The same
 * invalid Accept header and PUT form body must be masked there too.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecondTomcatLogMaskingTest extends ServerStartTestBase {

    private static final String SENTINEL = "sentinel-second-tomcat-3c9e";
    private static final String FIRST_ENGINE_NAME = "Tomcat";
    private static final String INVALID_ACCEPT = "Accept: " + SENTINEL;
    private static final String FORM_CONTENT_TYPE =
            "Content-Type: application/x-www-form-urlencoded";
    private static final String MASKED_ENDING = " : *****";
    // The servlet part of ...core.ContainerBase.[Tomcat-n].[localhost].[/].[<servlet>], which the
    // pattern keeps. The context logger [/] above it logs "Initializing Spring DispatcherServlet"
    // on the first request to a fresh server and is not masked.
    private static final String CONTAINER_LOGGER = "[/].[";
    private static final String DISPATCHER_SERVLET_LOGGER = "[dispatcherServlet]";

    private ConfigurableApplicationContext context;
    private int port;
    private int capturedBeforeRequest;

    /**
     * Starting and closing one server first makes the second one non-first whatever else ran in
     * this JVM.
     */
    @BeforeEach
    void startSecondTomcat(CapturedOutput output) {
        start(FREE_PORT).close();
        context = start(FREE_PORT);
        TomcatWebServer server =
                (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        Engine engine = server.getTomcat().getEngine();
        assertThat(engine.getName()).as("precondition").isNotEqualTo(FIRST_ENGINE_NAME);
        port = server.getPort();
        capturedBeforeRequest = output.length();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void invalid_accept_header_is_logged_masked(CapturedOutput output) throws IOException {
        Reply reply =
                send(
                        "POST",
                        SecurityTestBase.AGENT,
                        "{}",
                        SecurityTestBase.CONTENT_TYPE,
                        INVALID_ACCEPT);

        String captured = capturedSince(output);
        assertThat(captured.lines().filter(line -> line.contains(DISPATCHER_SERVLET_LOGGER)))
                .as("the event stays visible in: %s", captured)
                .isNotEmpty();
        assertNoUnmaskedLine(reply, captured);
    }

    @Test
    void invalid_accept_header_on_curator_leaves_no_unmasked_line(CapturedOutput output)
            throws IOException {
        Reply reply =
                send(
                        "POST",
                        SecurityTestBase.CURATOR,
                        "{}",
                        SecurityTestBase.CONTENT_TYPE,
                        INVALID_ACCEPT);

        assertNoUnmaskedLine(reply, capturedSince(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {SecurityTestBase.AGENT, SecurityTestBase.CURATOR})
    void invalid_put_form_body_leaves_no_unmasked_line(String endpoint, CapturedOutput output)
            throws IOException {
        Reply reply =
                send(
                        "PUT",
                        endpoint,
                        "a=" + SENTINEL + "%ZZ",
                        FORM_CONTENT_TYPE,
                        SecurityTestBase.ACCEPT);

        assertNoUnmaskedLine(reply, capturedSince(output));
    }

    private Reply send(
            String method, String endpoint, String body, String contentType, String accept)
            throws IOException {
        return RawHttp.send(
                port,
                HTTP_1_1,
                method,
                endpoint,
                body,
                "Host: 127.0.0.1:" + port,
                contentType,
                accept);
    }

    private String capturedSince(CapturedOutput output) {
        return output.getAll().substring(capturedBeforeRequest);
    }

    /**
     * A 403 would mean the request never got past the guard, and an empty log would then prove
     * nothing.
     */
    private static void assertNoUnmaskedLine(Reply reply, String captured) {
        assertThat(reply.status()).as(reply.body()).isNotEqualTo(403);
        assertThat(captured)
                .doesNotContain(SENTINEL)
                .doesNotContain("Exception")
                .doesNotContain("ZZ");
        assertThat(captured.lines().filter(line -> line.contains(CONTAINER_LOGGER)))
                .allSatisfy(line -> assertThat(line.strip()).endsWith(MASKED_ENDING));
    }
}
