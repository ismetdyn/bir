package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0020 §2, ADR-0022 §11: browser-reachable requests are refused on every path; real clients
 * still get through.
 */
class OriginGuardTest extends SecurityTestBase {

    static Stream<Arguments> origins() {
        return onBothEndpoints(
                "Origin: http://attacker.example",
                "Origin: http://127.0.0.1:" + PORT,
                "Origin: null",
                "Origin: ",
                "oRiGiN: http://attacker.example");
    }

    static Stream<Arguments> methodsOnBothEndpoints() {
        return onBothEndpoints("OPTIONS", "GET", "POST", "PUT", "DELETE", "HEAD");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("origins")
    void any_origin_header_is_forbidden_even_with_correct_host(String endpoint, String originLine)
            throws IOException {
        Reply reply = initialize(endpoint, host(), withPort(originLine));

        assertForbidden(reply);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("methodsOnBothEndpoints")
    void guard_applies_to_every_method_including_preflight(String endpoint, String method)
            throws IOException {
        Reply reply =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        method,
                        endpoint,
                        "",
                        host(),
                        "Origin: http://attacker.example",
                        ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(403);
        assertThat(reply.hasHeader("Access-Control-Allow-Origin")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/nope",
                "/hooks/session-start",
                "/api/v1/secrets/x",
                "/error",
                "/mcp/",
                "/mcp/curator/",
                "/mcp/curator/x",
                "/mcp;a=b",
                "/mcp/curator;a=b"
            })
    void guard_applies_to_any_path(String path) throws IOException {
        Reply forgedHost =
                RawHttp.send(
                        port, HTTP_1_1, "GET", path, "", "Host: attacker.example:" + port, ACCEPT);
        Reply withOrigin =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "GET",
                        path,
                        "",
                        host(),
                        "Origin: http://attacker.example",
                        ACCEPT);

        assertThat(forgedHost.status()).as("forged host: %s", forgedHost.body()).isEqualTo(403);
        assertThat(withOrigin.status()).as("origin: %s", withOrigin.body()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void forged_request_cannot_use_a_session_opened_by_a_real_client(String endpoint)
            throws IOException {
        String session = initialize(endpoint, host()).headers().get(SESSION_HEADER);
        assertThat(session).isNotBlank();

        Reply forgedHost =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        endpoint,
                        TOOLS_LIST,
                        "Host: attacker.example:" + port,
                        CONTENT_TYPE,
                        ACCEPT,
                        SESSION_HEADER + ": " + session);
        Reply withOrigin =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        endpoint,
                        TOOLS_LIST,
                        host(),
                        "Origin: http://attacker.example",
                        CONTENT_TYPE,
                        ACCEPT,
                        SESSION_HEADER + ": " + session);

        assertForbidden(forgedHost);
        assertForbidden(withOrigin);
        assertThat(forgedHost.body()).doesNotContain("\"tools\"");
        assertThat(withOrigin.body()).doesNotContain("\"tools\"");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("correctHostLines")
    void correct_host_without_origin_initializes(String endpoint, String hostLine)
            throws IOException {
        Reply reply = initialize(endpoint, withPort(hostLine));

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.headers().get(SESSION_HEADER)).isNotBlank();
        assertThat(reply.body()).contains("serverInfo");
    }

    static Stream<Arguments> correctHostLines() {
        return onBothEndpoints(
                "Host: 127.0.0.1:" + PORT, "Host: localhost:" + PORT, "host: 127.0.0.1:" + PORT);
    }

    @Test
    void unknown_path_with_correct_host_stays_not_found() throws IOException {
        Reply reply = RawHttp.send(port, HTTP_1_1, "GET", "/nope", "", host(), ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(404);
    }
}
