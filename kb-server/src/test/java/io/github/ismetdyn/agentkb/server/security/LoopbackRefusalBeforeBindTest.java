package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** ADR-0020 §3: a refused address must not open a socket, not even for a moment. */
class LoopbackRefusalBeforeBindTest extends ServerStartTestBase {

    /**
     * Holding the port makes any bind attempt fail loudly and deterministically, so a guard that
     * fires only after the socket was opened, even for a moment, cannot pass as a port conflict.
     * The control test below proves the detector.
     */
    @Test
    void refusal_comes_before_any_attempt_to_bind_the_socket() throws IOException {
        try (ServerSocket occupier = new ServerSocket(0)) {
            Throwable failure =
                    catchThrowable(
                            startAndStop(
                                    "--server.address=0.0.0.0",
                                    "--server.port=" + occupier.getLocalPort()));

            assertRefusedWithClearMessage(failure);
            assertThat(typesOf(failure)).doesNotContain("PortInUseException");
        }
    }

    @Test
    void control_binding_an_occupied_port_is_reported_as_port_in_use() throws IOException {
        try (ServerSocket occupier = new ServerSocket(0)) {
            Throwable failure =
                    catchThrowable(
                            startAndStop(
                                    "--server.address=127.0.0.1",
                                    "--server.port=" + occupier.getLocalPort()));

            assertThat(failure).isNotNull();
            assertThat(typesOf(failure)).contains("PortInUseException");
        }
    }

    @Test
    void nothing_listens_on_the_port_after_a_refusal() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        Throwable failure =
                catchThrowable(startAndStop("--server.address=0.0.0.0", "--server.port=" + port));

        assertThat(failure).isNotNull();
        assertThat(acceptsConnection(port)).isFalse();
    }
}
