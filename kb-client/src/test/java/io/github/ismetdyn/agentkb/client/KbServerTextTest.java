package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.HostileText.CONTROL;
import static io.github.ismetdyn.agentkb.client.HostileText.HOSTILE;
import static io.github.ismetdyn.agentkb.client.HostileText.assertStripped;
import static io.github.ismetdyn.agentkb.client.HostileText.escaped;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lead decision 6: kb strips terminal control characters from text the server sent before it prints
 * it, so a record or an error cannot move the cursor, clear the screen, retitle the window or hide
 * a line on the user's terminal. Newline and tab stay; the visible text around a stripped character
 * stays too.
 *
 * <p>The stub sends the characters JSON-escaped, as a real server would: ESC (CSI sequence), BEL,
 * carriage return, backspace, DEL and the C1 CSI U+009B, which many terminals take as ESC [.
 */
class KbServerTextTest {

    @TempDir Path workDir;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void check_prints_a_problem_without_its_control_characters() {
        server.answer(
                "GET",
                "/api/v1/check",
                200,
                "{\"clean\":false,\"problems\":[{\"kind\":\"orphan-edge\",\"id\":\"01KBTESTEDGE\","
                        + "\"detail\":\""
                        + HOSTILE
                        + "\"}]}");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertStripped(result.stdout());
    }

    @Test
    void revert_prints_its_answer_without_control_characters() {
        server.answer(
                "POST",
                "/api/v1/revert",
                200,
                "{\"batch\":\""
                        + HOSTILE
                        + "\",\"deleted\":1,\"reopened\":0,\"deletedSecretValues\":0}");

        Result result = server.kb(workDir, "revert", "--batch", "01KBTESTBATCH");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertStripped(result.stdout());
    }

    @Test
    void sync_prints_its_commit_without_control_characters() {
        // The restore answer holds numbers only; the sync commit is the text a success answer
        // prints.
        server.answer(
                "POST",
                "/api/v1/sync",
                200,
                "{\"commit\":\""
                        + HOSTILE
                        + "\",\"pushed\":false,\"nodes\":1,\"edges\":0,\"conflicts\":0}");

        Result result = server.kb(workDir, "sync");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertStripped(result.stdout());
    }

    /** A refusal's error and hint go to stderr; they are server text too. */
    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void a_refusal_is_printed_without_control_characters(int status) {
        server.answer(
                "GET",
                "/api/v1/check",
                status,
                "{\"error\":\"" + HOSTILE + "\",\"hint\":\"" + HOSTILE + "\"}");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).isEqualTo(1);
        assertStripped(result.stderr());
    }

    /** A body that is not JSON is printed as it came, minus its control characters. */
    @Test
    void a_non_json_error_body_is_printed_without_control_characters() {
        server.answer(
                "GET", "/api/v1/check", 502, "kbtest-before\u001b[2J\u0007\rkbtest-after\u009b31m");

        Result result = server.kb(workDir, "check");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("kbtest-before", "kbtest-after");
        assertThat(CONTROL.matcher(result.stderr()).find())
                .as("control character in %s", escaped(result.stderr()))
                .isFalse();
    }

    /** Only control characters go: tab and newline inside a detail stay as they are. */
    @Test
    void tab_and_newline_in_server_text_are_kept() {
        server.answer(
                "GET",
                "/api/v1/check",
                200,
                "{\"clean\":false,\"problems\":[{\"kind\":\"git-fsck\",\"id\":\"repo\","
                        + "\"detail\":\"kbtest-line-one\\tcolumn\\nkbtest-line-two\"}]}");

        Result result = server.kb(workDir, "check");

        assertThat(result.stdout()).contains("kbtest-line-one\tcolumn\nkbtest-line-two");
    }
}
