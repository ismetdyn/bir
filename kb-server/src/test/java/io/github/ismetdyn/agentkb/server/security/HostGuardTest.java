package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_0;
import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0020 §2, ADR-0022 §11: only the loopback name and the real port pass as Host, whatever else
 * arrives.
 */
class HostGuardTest extends SecurityTestBase {

    /** Hosts Tomcat lets through, so only the guard can stop them. */
    static Stream<Arguments> forgedHosts() {
        return onBothEndpoints(
                "attacker.example:" + PORT,
                "127.0.0.1.attacker.example:" + PORT,
                "127.0.0.1:1",
                "localhost:1",
                // The port comes from the connection, not from the documented default.
                "127.0.0.1:7411",
                "localhost:7411",
                "127.0.0.1",
                "localhost",
                "LOCALHOST:" + PORT,
                "LocalHost:" + PORT,
                "localhost.:" + PORT,
                "[::1]:" + PORT,
                "0.0.0.0:" + PORT,
                "127.1:" + PORT,
                "127.0.0.1:0" + PORT,
                "");
    }

    /**
     * Hosts Tomcat answers itself before any filter runs; either refusal is fine, the MCP server
     * must not answer.
     */
    static Stream<Arguments> malformedHostHeaderLines() {
        return onBothEndpoints(
                "",
                "Host: 127.0.0.1:" + PORT + "\nHost: 127.0.0.1:" + PORT,
                "Host: 127.0.0.1:" + PORT + "\nHost: attacker.example:" + PORT,
                "Host: attacker.example:" + PORT + "\nHost: 127.0.0.1:" + PORT,
                "Host: 127.0.0.1:" + PORT + ", attacker.example:" + PORT,
                "Host: 127.0.0.1:" + PORT + "@attacker.example",
                "Host: bad host");
    }

    @ParameterizedTest(name = "{0} Host: {1}")
    @MethodSource("forgedHosts")
    void forged_host_is_forbidden(String endpoint, String host) throws IOException {
        Reply reply = initialize(endpoint, "Host: " + withPort(host));

        assertForbidden(reply);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("malformedHostHeaderLines")
    void malformed_host_never_reaches_the_mcp_server(String endpoint, String hostLines)
            throws IOException {
        Reply reply = initialize(endpoint, withPort(hostLines));

        assertThat(reply.status()).as(reply.body()).isIn(400, 403);
        assertNoMcpAnswer(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void request_without_any_host_is_forbidden(String endpoint) throws IOException {
        // Only HTTP/1.0 may omit Host past Tomcat; there is nothing to allow-list, so it must not
        // pass.
        Reply reply =
                RawHttp.send(port, HTTP_1_0, "POST", endpoint, INITIALIZE, CONTENT_TYPE, ACCEPT);

        assertForbidden(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void absolute_uri_cannot_override_the_host_check(String endpoint) throws IOException {
        Reply reply =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        "http://attacker.example:" + port + endpoint,
                        INITIALIZE,
                        host(),
                        CONTENT_TYPE,
                        ACCEPT);

        assertThat(reply.status()).as(reply.body()).isIn(400, 403);
        assertNoMcpAnswer(reply);
    }
}
