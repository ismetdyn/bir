package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.abbreviate;
import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
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
 * Plan G1: an error response outside a session never shows internals, whichever layer produced it.
 */
class ErrorBodyLeakTest extends SecurityTestBase {

    private static final String UNKNOWN_METHOD =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}";

    private record ErrorCase(String label, String method, String body, String[] headers) {

        static ErrorCase of(String label, String method, String body, String... headers) {
            return new ErrorCase(label, method, body, headers);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> mcpErrorPaths() {
        return Stream.of("/mcp", "/mcp/curator")
                .flatMap(
                        endpoint ->
                                Stream.of(
                                                ErrorCase.of(
                                                        "malformed json",
                                                        "POST",
                                                        "{not json",
                                                        CONTENT_TYPE,
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "empty body",
                                                        "POST",
                                                        "",
                                                        CONTENT_TYPE,
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "json array",
                                                        "POST",
                                                        "[1,2]",
                                                        CONTENT_TYPE,
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "no session",
                                                        "POST",
                                                        UNKNOWN_METHOD,
                                                        CONTENT_TYPE,
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "unknown session",
                                                        "POST",
                                                        TOOLS_LIST,
                                                        CONTENT_TYPE,
                                                        ACCEPT,
                                                        SESSION_HEADER + ": x"),
                                                ErrorCase.of(
                                                        "wrong content type",
                                                        "POST",
                                                        TOOLS_LIST,
                                                        "Content-Type: text/plain",
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "wrong accept",
                                                        "POST",
                                                        TOOLS_LIST,
                                                        CONTENT_TYPE,
                                                        "Accept: text/plain"),
                                                ErrorCase.of(
                                                        "get without session", "GET", "", ACCEPT),
                                                ErrorCase.of(
                                                        "delete without session",
                                                        "DELETE",
                                                        "",
                                                        ACCEPT),
                                                ErrorCase.of(
                                                        "unsupported method",
                                                        "PUT",
                                                        TOOLS_LIST,
                                                        CONTENT_TYPE,
                                                        ACCEPT))
                                        .map(errorCase -> Arguments.of(endpoint, errorCase)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("mcpErrorPaths")
    void mcp_error_response_carries_no_internal_detail(String endpoint, ErrorCase errorCase)
            throws IOException {
        String[] headers =
                Stream.concat(Stream.of(host()), Stream.of(errorCase.headers()))
                        .toArray(String[]::new);

        Reply reply =
                RawHttp.send(
                        port, HTTP_1_1, errorCase.method(), endpoint, errorCase.body(), headers);

        assertThat(reply.status()).as(reply.body()).isBetween(400, 599);
        assertNoLeak(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/mcp/curator", "/nope"})
    void forbidden_response_for_forged_host_carries_no_internal_detail(String path)
            throws IOException {
        Reply reply =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        path,
                        "{}",
                        "Host: attacker.example:" + port,
                        CONTENT_TYPE,
                        ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(403);
        assertNoLeak(reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/mcp/curator", "/nope"})
    void forbidden_response_for_origin_carries_no_internal_detail(String path) throws IOException {
        Reply reply =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        path,
                        "{}",
                        host(),
                        "Origin: http://attacker.example",
                        CONTENT_TYPE,
                        ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(403);
        assertNoLeak(reply);
    }

    /**
     * Invariant 2: a rejected payload may carry a secret, and error messages are one of the outputs
     * it must stay out of.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/mcp/curator"})
    void rejected_payload_is_not_echoed_in_the_error_response(String endpoint) throws IOException {
        String sentinel = "sentinel-rejected-payload-7f3a";
        String body = "{\"not_a_jsonrpc_message\":\"" + sentinel + "\"}";

        Reply reply =
                RawHttp.send(port, HTTP_1_1, "POST", endpoint, body, host(), CONTENT_TYPE, ACCEPT);

        assertThat(reply.status()).as(abbreviate(reply.body())).isEqualTo(400);
        assertThat(reply.body()).doesNotContain(sentinel);
    }

    /**
     * Boot's error attributes honour these parameters only when server.error.include-* is on-param
     * or always.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/nope", "/error", "/mcp/nope"})
    void framework_error_page_ignores_requests_for_detail(String path) throws IOException {
        String target = path + "?trace=true&message=true&errors=true";

        Reply json =
                RawHttp.send(port, HTTP_1_1, "GET", target, "", host(), "Accept: application/json");
        Reply html = RawHttp.send(port, HTTP_1_1, "GET", target, "", host(), "Accept: text/html");

        assertThat(json.status()).as(json.body()).isBetween(400, 599);
        assertNoLeak(json);
        assertThat(html.status()).as(html.body()).isBetween(400, 599);
        assertNoLeak(html);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%zz", "/mcp%2fcurator", "/mcp%00", "/..;/x"})
    void container_rejection_page_carries_no_internal_detail(String path) throws IOException {
        Reply reply = RawHttp.send(port, HTTP_1_1, "GET", path, "", host(), ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply);
    }

    @Test
    void duplicated_host_rejection_page_carries_no_internal_detail() throws IOException {
        Reply reply = RawHttp.send(port, HTTP_1_1, "GET", "/mcp", "", host(), host(), ACCEPT);

        assertThat(reply.status()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply);
    }
}
