package io.github.ismetdyn.agentkb.client;

import static io.github.ismetdyn.agentkb.client.HostileText.HOSTILE;
import static io.github.ismetdyn.agentkb.client.HostileText.assertStripped;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decision 6 for the plan.md H6 read commands: record text in a search hit and the Markdown of
 * kb show reach the terminal without control characters (KbServerTextTest has the F2 commands); so
 * does the error body kb secret show prints when the server refuses (H6 review, CWE-451).
 */
class KbReadTextTest {

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

    /** A search snippet is record text the server quotes (plan.md H6). */
    @Test
    void search_prints_a_snippet_without_control_characters() {
        server.answer(
                "GET",
                "/api/v1/search",
                200,
                "{\"hits\":[{\"id\":\"kbtest-a\",\"type\":\"Server\",\"tier\":\"current\","
                        + "\"archived\":false,\"snippet\":\""
                        + HOSTILE
                        + "\"}],\"more\":0}");

        Result result = server.kb(workDir, "search", "kbtest");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertStripped(result.stdout());
    }

    /** kb show prints the server's Markdown as it came, but not its control characters. */
    @Test
    void show_prints_markdown_without_control_characters() {
        server.answer(
                "GET",
                "/api/v1/entities/kbtest-a",
                200,
                "# kbtest-before\u001b[2J\u001b]0;kbtest-title\u0007\rkbtest-after\b\u007f\u009b31m"
                        + "\u2067kbtest-reversed\u2069\n");

        Result result = server.kb(workDir, "show", "kbtest-a");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertStripped(result.stdout());
    }

    /** SecretApi prints a refusal's body as it came; its control characters go too. */
    @Test
    void a_secret_refusal_is_printed_without_control_characters() {
        server.answer(
                "GET",
                "/api/v1/secrets/kbtest-a/prod/x",
                500,
                "kbtest-before\u001b[2J\u001b]0;kbtest-title\u0007\rkbtest-after\b\u007f\u009b31m"
                        + "\u202ekbtest-reversed");

        Result result = server.kb(workDir, "secret", "show", "kbtest-a/prod/x");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertStripped(result.stderr());
    }
}
