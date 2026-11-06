package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * plan.md H2: JsonApi gains PUT, PATCH and DELETE with JSON bodies (CLIENTS §1: {@code PATCH
 * /entities/{id}}) and a raw byte request for the resumable upload (ADR-0025 decision 1: {@code PUT
 * /uploads/{sha}} with {@code Upload-Length}, {@code PATCH} with {@code
 * application/offset+octet-stream} and {@code Upload-Offset}, {@code DELETE}). The rules of the F2
 * methods hold for the new ones: non-2xx is exit 1 with {@code error; hint}, an unreachable server
 * exit 3, server text without control characters. A 2xx without a body is an empty answer, not an
 * error.
 *
 * <p>The contract this pins, for the H2 developer: a package-private {@code JsonApi(String base)};
 * {@code Map<?, ?> put/patch(String path, String json)}; {@code Map<?, ?> delete(String path)};
 * {@code Reply request(String method, String path, Map<String, String> headers, byte[] body)} with
 * {@code record Reply(int status, java.net.http.HttpHeaders headers, Map<?, ?> body)}.
 */
class JsonApiMethodsTest {

    private static final String UPLOAD =
            "/api/v1/uploads/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String OCTETS = "application/offset+octet-stream";

    private ApiStub server;
    private JsonApi api;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
        api = new JsonApi(server.url());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void put_sends_its_json_body_and_returns_the_answer() {
        server.answer("PUT", "/api/v1/entities/kbtest-a", 200, "{\"id\":\"kbtest-a\"}");

        Map<?, ?> answer = api.put("/api/v1/entities/kbtest-a", "{\"summary\": \"ğİ\"}");

        assertThat(answer.get("id")).isEqualTo("kbtest-a");
        ApiStub.Request sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("PUT");
        assertThat(sent.headers().get("Content-type")).containsExactly("application/json");
        assertThat(sent.body()).isEqualTo("{\"summary\": \"ğİ\"}");
    }

    /** The answer's text comes back without control characters; tab and newline stay. */
    @Test
    void patch_sends_its_json_body_and_returns_the_answer_without_control_characters() {
        server.answer(
                "PATCH",
                "/api/v1/entities/kbtest-a",
                200,
                "{\"aliases\":[\"a\"],\"summary\":\"a\\u001b]0;title\\u0007b\\tc\\nd\"}");

        Map<?, ?> answer = api.patch("/api/v1/entities/kbtest-a", "{\"aliases\": [\"a\"]}");

        assertThat(answer.get("aliases")).isEqualTo(List.of("a"));
        assertThat(answer.get("summary")).isEqualTo("a]0;titleb\tc\nd");
        ApiStub.Request sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(sent.headers().get("Content-type")).containsExactly("application/json");
        assertThat(sent.body()).isEqualTo("{\"aliases\": [\"a\"]}");
    }

    @Test
    void delete_with_no_content_returns_an_empty_answer() {
        server.answer("DELETE", UPLOAD, 204, "");

        Map<?, ?> answer = api.delete(UPLOAD);

        assertThat(answer).isEmpty();
        ApiStub.Request sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("DELETE");
        assertThat(sent.bytes()).isEmpty();
    }

    /** Every byte value, CR, LF and NUL arrive as sent: the body is not text. */
    @Test
    void a_raw_patch_sends_its_bytes_and_headers_and_reads_the_answer_headers() {
        byte[] chunk = new byte[256];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) i;
        }
        server.answer("PATCH", UPLOAD, 204, "", Map.of("Upload-Offset", "1280"));

        JsonApi.Reply reply =
                api.request(
                        "PATCH",
                        UPLOAD,
                        Map.of("Content-Type", OCTETS, "Upload-Offset", "1024"),
                        chunk);

        assertThat(reply.status()).isEqualTo(204);
        assertThat(reply.headers().firstValue("upload-offset")).contains("1280");
        assertThat(reply.body()).isEmpty();
        ApiStub.Request sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(sent.bytes()).isEqualTo(chunk);
        assertThat(sent.headers().get("Content-type")).containsExactly(OCTETS);
        assertThat(sent.headers().get("Upload-offset")).containsExactly("1024");
    }

    /** 201 (a new upload) and 200 (one that exists) stay apart: kb doc add needs both. */
    @Test
    void a_raw_put_without_a_body_keeps_the_status_and_answer() {
        server.answer("PUT", UPLOAD, 201, "{\"offset\":0}", Map.of("Upload-Offset", "0"));

        JsonApi.Reply reply =
                api.request("PUT", UPLOAD, Map.of("Upload-Length", "2048"), new byte[0]);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.headers().firstValue("Upload-Offset")).contains("0");
        assertThat(reply.body().get("offset")).isNotNull();
        ApiStub.Request sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("PUT");
        assertThat(sent.bytes()).isEmpty();
        assertThat(sent.headers().get("Upload-length")).containsExactly("2048");
    }

    static Stream<Arguments> calls() {
        return Stream.of(
                Arguments.of("PUT", (Consumer<JsonApi>) api -> api.put(UPLOAD, "{}")),
                Arguments.of("PATCH", (Consumer<JsonApi>) api -> api.patch(UPLOAD, "{}")),
                Arguments.of("DELETE", (Consumer<JsonApi>) api -> api.delete(UPLOAD)),
                Arguments.of(
                        "PATCH",
                        (Consumer<JsonApi>)
                                api ->
                                        api.request(
                                                "PATCH",
                                                UPLOAD,
                                                Map.of("Content-Type", OCTETS),
                                                new byte[] {1, 2, 3})));
    }

    /** A refusal (an offset that does not match, a body too large) is exit 1 with its hint. */
    @ParameterizedTest(name = "{0} #{index}")
    @MethodSource("calls")
    void a_refusal_is_exit_one_with_error_and_hint(String method, Consumer<JsonApi> call) {
        server.answer(
                method,
                UPLOAD,
                409,
                "{\"error\":\"the offset is not \\u001b[2J1024\",\"hint\":\"Resume at 1024.\"}");

        KbException refused = catchThrowableOfType(KbException.class, () -> call.accept(api));

        assertThat(refused).isNotNull();
        assertThat(refused.exitCode()).isEqualTo(Kb.FAILED);
        assertThat(refused.getMessage())
                .contains("409", "the offset is not", "1024", "Resume at 1024.")
                .doesNotContain("\u001b");
    }

    @ParameterizedTest(name = "{0} #{index}")
    @MethodSource("calls")
    void an_unreachable_server_is_exit_three(String method, Consumer<JsonApi> call)
            throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }
        JsonApi down = new JsonApi(closed);

        KbException refused = catchThrowableOfType(KbException.class, () -> call.accept(down));

        assertThat(refused).isNotNull();
        assertThat(refused.exitCode()).isEqualTo(Kb.UNREACHABLE);
    }
}
