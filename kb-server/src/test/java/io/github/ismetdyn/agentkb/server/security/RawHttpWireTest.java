package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Without these, a client that silently rewrote Host would make every Host test of this package
 * meaningless.
 */
class RawHttpWireTest extends SecurityTestBase {

    @Test
    void raw_client_writes_forged_headers_verbatim_on_the_wire() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received =
                    CompletableFuture.supplyAsync(() -> answerOnce(listener));

            RawHttp.send(
                    listener.getLocalPort(),
                    HTTP_1_1,
                    "POST",
                    AGENT,
                    "",
                    "Host: attacker.example:1234",
                    "Origin: http://attacker.example");

            assertThat(received.get())
                    .contains("\r\nHost: attacker.example:1234\r\n")
                    .contains("\r\nOrigin: http://attacker.example\r\n");
        }
    }

    /**
     * Tomcat compares an absolute request URI with the Host header, so a 400 only on the
     * mismatching value proves the forged Host arrives at the real server unchanged, with or
     * without the guard.
     */
    @ParameterizedTest
    @ValueSource(strings = {AGENT, CURATOR})
    void running_server_receives_the_host_value_the_raw_client_sends(String endpoint)
            throws IOException {
        String absoluteUri = "http://127.0.0.1:" + port + endpoint;

        Reply matching =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        absoluteUri,
                        INITIALIZE,
                        host(),
                        CONTENT_TYPE,
                        ACCEPT);
        Reply mismatching =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "POST",
                        absoluteUri,
                        INITIALIZE,
                        "Host: attacker.example:" + port,
                        CONTENT_TYPE,
                        ACCEPT);

        assertThat(matching.status()).as(matching.body()).isEqualTo(200);
        assertThat(mismatching.status()).as(mismatching.body()).isEqualTo(400);
    }

    private static String answerOnce(ServerSocket listener) {
        try (Socket client = listener.accept()) {
            byte[] buffer = new byte[4096];
            int read = client.getInputStream().read(buffer);
            byte[] emptyReply =
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1);
            client.getOutputStream().write(emptyReply);
            return new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
