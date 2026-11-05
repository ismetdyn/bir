package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ADR-0020 §3: without tokens a network-facing server exposes the whole knowledge base, so it must
 * refuse to start.
 */
class LoopbackRequiredTest extends ServerStartTestBase {

    private static final String NON_LOOPBACK_HOST = HostsForTest.RESOLVES_ELSEWHERE;

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.0.2.1", NON_LOOPBACK_HOST})
    void refuses_to_start_on_non_loopback_address(String address) {
        Throwable failure = catchThrowable(startAndStop("--server.address=" + address, FREE_PORT));

        assertRefusedWithClearMessage(failure);
        assertThat(messagesOf(failure)).as("message names the offending value").contains(address);
    }

    /**
     * The web server takes its address from the Binder, which also accepts these spellings, and
     * they outrank the yml.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "--server.Address=0.0.0.0",
                "--server.ADDRESS=0.0.0.0",
                "--SERVER.ADDRESS=0.0.0.0"
            })
    void refuses_to_start_on_non_loopback_address_in_a_relaxed_spelling(String argument) {
        Throwable failure = catchThrowable(startAndStop(argument, FREE_PORT));

        assertRefusedWithClearMessage(failure);
        assertThat(messagesOf(failure)).as("message names the offending value").contains("0.0.0.0");
    }

    @Test
    void test_resolver_really_maps_the_hostname_outside_loopback() throws IOException {
        assertThat(InetAddress.getByName(NON_LOOPBACK_HOST).isLoopbackAddress()).isFalse();
    }

    /** An empty override resolves to no address, and Tomcat then binds every interface. */
    @Test
    void refuses_to_start_on_blank_address() {
        assertRefusedWithClearMessage(catchThrowable(startAndStop("--server.address=", FREE_PORT)));
    }

    /**
     * Skipping application.yml is the only way to truly unset the key; the rest of its settings
     * come as arguments.
     */
    @Test
    void refuses_to_start_when_address_is_unset() {
        Throwable failure =
                catchThrowable(
                        startAndStop(
                                "--spring.config.location=optional:classpath:/no-such-file.yml",
                                FREE_PORT,
                                "--spring.ai.mcp.server.name=agentkb",
                                "--spring.ai.mcp.server.version=0.1.0",
                                "--spring.ai.mcp.server.type=SYNC",
                                "--spring.ai.mcp.server.protocol=STREAMABLE",
                                "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp"));

        assertRefusedWithClearMessage(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost"})
    void starts_on_loopback_and_accepts_connections(String address) throws IOException {
        try (ConfigurableApplicationContext context =
                start("--server.address=" + address, FREE_PORT)) {
            int port =
                    context.getEnvironment()
                            .getRequiredProperty("local.server.port", Integer.class);

            assertThat(acceptsConnection(port)).isTrue();
        }
    }
}
