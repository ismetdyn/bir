package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** K2: the server listens on loopback only, by default configuration, proven at socket level. */
class ServerBindingTest extends RunningServerTestBase {

    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final String TCP_STATE_LISTEN = "0A";
    // /proc prints 127.0.0.1 in little-endian hex; the IPv4-mapped IPv6 form has the same suffix, a wildcard has not.
    private static final String LOOPBACK_V4_HEX_SUFFIX = "0100007F";

    @Autowired
    private Environment environment;

    @Test
    void application_yml_defaults_to_loopback_and_port_7411() throws IOException {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(yaml.getProperty("server.address")).hasToString("127.0.0.1");
        assertThat(yaml.getProperty("server.port")).hasToString("7411");
    }

    @Test
    void running_server_takes_its_address_from_application_yml_default() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void accepts_connection_on_loopback() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MILLIS);

            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void refuses_connection_on_every_non_loopback_local_address() throws IOException {
        List<InetAddress> addresses = nonLoopbackLocalAddresses();
        assumeFalse(addresses.isEmpty(), "no non-loopback interface is up");

        for (InetAddress address : addresses) {
            assertThatThrownBy(() -> {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                        }
                    })
                    .as("connection to %s:%d", address.getHostAddress(), port)
                    .isInstanceOf(ConnectException.class);
        }
    }

    @Test
    void kernel_reports_listening_socket_on_loopback_and_not_on_wildcard() throws IOException {
        Path tcp4 = Path.of("/proc/self/net/tcp");
        Path tcp6 = Path.of("/proc/self/net/tcp6");
        assumeTrue(Files.isReadable(tcp4), "/proc/self/net is Linux only");

        List<String> localAddresses = new ArrayList<>(listeningAddressesOnPort(tcp4));
        if (Files.isReadable(tcp6)) {
            localAddresses.addAll(listeningAddressesOnPort(tcp6));
        }

        assertThat(localAddresses).isNotEmpty().allMatch(address -> address.endsWith(LOOPBACK_V4_HEX_SUFFIX));
    }

    private static List<InetAddress> nonLoopbackLocalAddresses() throws IOException {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (networkInterface.isUp()) {
                Collections.list(networkInterface.getInetAddresses()).stream()
                        .filter(address -> !address.isLoopbackAddress())
                        .forEach(addresses::add);
            }
        }
        return addresses;
    }

    private List<String> listeningAddressesOnPort(Path procNetFile) throws IOException {
        String portSuffix = String.format(Locale.ROOT, ":%04X", port);
        return Files.readAllLines(procNetFile).stream()
                .skip(1)
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns[3].equals(TCP_STATE_LISTEN) && columns[1].endsWith(portSuffix))
                .map(columns -> columns[1].substring(0, columns[1].indexOf(':')))
                .toList();
    }
}
