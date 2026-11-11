package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/CLIENTS.md (F2: only {@code set} and {@code show}) and docs/SECRETS.md §4. {@code kb secret
 * set <id>} reads the value without echo: from the console, or from stdin when stdin is not a
 * terminal (these tests), one trailing line end dropped the way {@code read} does. It sends {@code
 * PUT /api/v1/secrets/<id>} with the value as the body. {@code kb secret show <id>} prints the
 * value for a human. The values are fake.
 */
class KbSecretTest {

    private static final String ID = "ornek-db/prod/app-password";
    private static final String VALUE = "kbtest-FAKE-9c2e51d8-şİı€";
    private static final String PEM_LIKE = "-----FAKE KEY-----\nAAAAkbtest\n-----END FAKE-----";

    @TempDir Path workDir;

    private SecretStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new SecretStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    static Stream<Arguments> stdinValues() {
        return Stream.of(
                arguments(VALUE + "\n", VALUE),
                arguments(VALUE + "\r\n", VALUE),
                arguments(VALUE, VALUE),
                arguments(PEM_LIKE + "\n", PEM_LIKE));
    }

    @ParameterizedTest
    @MethodSource("stdinValues")
    void set_sends_the_value_from_stdin_and_never_echoes_it(String stdin, String stored) {
        Result result = KbProcess.run(server.url(), stdin, workDir, "secret", "set", ID);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.stored(ID)).isEqualTo(stored);
        assertThat(result.stdout() + result.stderr()).doesNotContain("kbtest");
        assertThat(server.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("PUT");
                            assertThat(request.rawPath()).isEqualTo(SecretStub.PREFIX + ID);
                            assertThat(request.headers().keySet())
                                    .noneMatch("Authorization"::equalsIgnoreCase);
                        });
    }

    /**
     * ADR-0026: the value was already in records, so the server stores it and answers 200 with a
     * note to rotate it; kb prints the note to stderr, without its control characters, and
     * succeeds.
     */
    @Test
    void set_prints_the_servers_note_and_succeeds() {
        server.noteOnPut("The value is stored. It was already in 2 records: rotate it.\u001b[2J\n");

        Result result = KbProcess.run(server.url(), VALUE + "\n", workDir, "secret", "set", ID);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.stored(ID)).isEqualTo(VALUE);
        assertThat(result.stderr())
                .contains("already in 2 records: rotate it.")
                .doesNotContain("\u001b");
        assertThat(result.stdout()).contains("stored; use it as kb:secret/" + ID);
    }

    @Test
    void set_with_no_value_sends_nothing() {
        Result result = KbProcess.run(server.url(), "\n", workDir, "secret", "set", ID);

        assertThat(result.exitCode()).isNotZero();
        assertThat(server.requests()).isEmpty();
    }

    /** UTF-8 even under the POSIX locale, so the value can be piped exactly. */
    @Test
    void show_prints_the_value() {
        server.with(ID, VALUE);

        Result result = KbProcess.run(server.url(), "", workDir, "secret", "show", ID);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isIn(VALUE, VALUE + "\n");
    }

    @Test
    void show_of_a_value_not_on_this_machine_fails_and_names_the_id() {
        Result result = KbProcess.run(server.url(), "", workDir, "secret", "show", ID);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(ID).doesNotContain("Exception");
    }
}
