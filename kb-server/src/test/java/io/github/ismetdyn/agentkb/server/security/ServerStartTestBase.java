package io.github.ismetdyn.agentkb.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The application started the way a user starts it: through {@link SpringApplication} with
 * command-line arguments, which outrank application.yml. Each start owns a data directory because a
 * cached test context holds its Neo4j lock.
 */
abstract class ServerStartTestBase {

    protected static final String FREE_PORT = "--server.port=0";
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;

    @TempDir private Path dataDir;

    protected ThrowingCallable startAndStop(String... args) {
        return () -> start(args).close();
    }

    protected ConfigurableApplicationContext start(String... args) {
        String[] allArgs =
                Stream.concat(Stream.of(args), Stream.of("--agentkb.data-dir=" + dataDir))
                        .toArray(String[]::new);
        return new SpringApplication(AgentkbServerApplication.class).run(allArgs);
    }

    protected static void assertRefusedWithClearMessage(Throwable failure) {
        assertThat(failure).as("the server started instead of refusing").isNotNull();
        assertThat(messagesOf(failure)).as("names the setting").contains("server.address");
        assertThat(messagesOf(failure)).as("names the rule").containsIgnoringCase("loopback");
    }

    protected static boolean acceptsConnection(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (ConnectException e) {
            return false;
        }
    }

    protected static String messagesOf(Throwable failure) {
        return chainOf(failure).stream()
                .map(Throwable::getMessage)
                .collect(Collectors.joining(" | "));
    }

    protected static List<String> typesOf(Throwable failure) {
        return chainOf(failure).stream().map(t -> t.getClass().getSimpleName()).toList();
    }

    private static List<Throwable> chainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure;
                current != null && !chain.contains(current);
                current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
