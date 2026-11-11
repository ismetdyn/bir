package io.github.ismetdyn.agentkb.client;

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
 * H6 review: how kb reads a command line (docs/CLIENTS.md §2). A negative number is a value; any
 * other value that starts with {@code -} reads as an unknown option (exit 1) unless {@code --}
 * comes first. Only kb run stops at its command: what follows is the command's, {@code --} or not.
 * A group without its subcommand (kb, kb fact, kb rel, kb secret) prints its usage, exit 1.
 */
class KbCommandLineTest {

    private static final String FACTS = "/api/v1/entities/kbtest-a/facts";

    @TempDir Path workDir;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
        server.answer("POST", FACTS, 200, KbCommandCases.contract("write-answer.json"));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void a_negative_number_is_a_value() {
        Result result = server.kb(workDir, "fact", "set", "kbtest-a", "offset", "-1");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.requests().getFirst().body())
                .containsPattern(ApiStub.jsonField("value", "-1"));
    }

    @Test
    void a_value_like_an_option_is_refused_without_a_double_dash() {
        Result result = server.kb(workDir, "fact", "set", "kbtest-a", "flags", "-x");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void a_value_like_an_option_is_sent_after_a_double_dash() {
        Result result = server.kb(workDir, "fact", "set", "kbtest-a", "flags", "--", "-x");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(server.requests().getFirst().body())
                .containsPattern(ApiStub.jsonField("value", "-x"));
    }

    /** No placeholder, so kb run asks the server nothing and runs the command as given. */
    @Test
    void run_without_a_double_dash_gives_the_command_everything_after_its_name() {
        Result result = server.kb(workDir, "run", "echo", "kbtest", "--env", "x");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("kbtest --env x\n");
        assertThat(server.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fact", "rel", "secret"})
    void a_group_without_its_subcommand_prints_usage_and_exits_one(String group) {
        Result result = group.isEmpty() ? server.kb(workDir) : server.kb(workDir, group.split(" "));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(result.stderr()).contains("Missing required subcommand", "Usage: kb");
        assertThat(server.requests()).isEmpty();
    }
}
