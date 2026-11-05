package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.KbRunTest.ID;
import static io.github.ismetdyn.agentkb.client.KbRunTest.VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/SECRETS.md §3: {@code kb run} masks the value, its base64 and its URL-encoded forms as
 * {@code ****} in the child's stdout and stderr, also when the value arrives in pieces, and passes
 * every other byte unchanged. The child gets the value as an argument here, so what it prints does
 * not depend on how it would have computed it. The values are fake.
 */
class KbRunMaskingTest {

    static final String MASK = "****";
    private static final List<String> ENV = List.of("--env", "PASS=kb:secret/" + ID);

    @TempDir Path workDir;

    private SecretStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new SecretStub().with(ID, VALUE);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /**
     * The child writes UTF-8 bytes by octal escape (ş İ, ç ı): a JVM under the POSIX locale would
     * turn non-ASCII arguments into '?' before the child sees them.
     */
    @Test
    void the_value_is_masked_on_stdout_and_stderr_and_other_bytes_pass_unchanged() {
        Result result =
                kbRun(
                        "printf '\\305\\237 %s \\304\\260\\n' \"$PASS\";"
                                + " printf '\\303\\247 %s \\304\\261\\n' \"$PASS\" >&2");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("ş " + MASK + " İ\n");
        assertThat(result.stderr()).isEqualTo("ç " + MASK + " ı\n");
    }

    static Stream<String> encodedForms() {
        String formEncoded = URLEncoder.encode(VALUE, StandardCharsets.UTF_8);
        return Stream.of(
                Base64.getEncoder().encodeToString(VALUE.getBytes(StandardCharsets.UTF_8)),
                formEncoded,
                formEncoded.replace("+", "%20"));
    }

    @ParameterizedTest
    @MethodSource("encodedForms")
    void an_encoded_form_of_the_value_is_masked(String encoded) {
        Result result = kbRun("printf 'x %s y\\n' \"$1\"; printf 'x %s y\\n' \"$1\" >&2", encoded);

        assertThat(result.stdout()).isEqualTo("x " + MASK + " y\n");
        assertThat(result.stderr()).isEqualTo("x " + MASK + " y\n");
    }

    /** The two halves arrive in separate reads; masking line by line or read by read misses it. */
    @Test
    void a_value_split_across_reads_is_masked() {
        int half = VALUE.length() / 2;
        Result result =
                kbRun(
                        "printf %s \"$1\"; sleep 1; printf '%s\\n' \"$2\"",
                        VALUE.substring(0, half), VALUE.substring(half));

        assertThat(result.stdout()).isEqualTo(MASK + "\n");
    }

    /** A masker holds back a possible prefix of the value; at the end it must let it out. */
    @Test
    void output_ending_in_a_prefix_of_the_value_is_passed_whole() {
        String prefix = VALUE.substring(0, VALUE.length() - 1);

        Result result = kbRun("printf 'tail %s' \"$1\"", prefix);

        assertThat(result.stdout()).isEqualTo("tail " + prefix);
    }

    private Result kbRun(String script, String... scriptArgs) {
        return KbProcess.run(server.url(), "", workDir, KbProcess.runArgs(ENV, script, scriptArgs));
    }
}
