package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbCommandCases.Case;
import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * plan.md H6, client side: each kb command sends its one REST call (docs/CLIENTS.md §1) and prints
 * the answer as plain text for a person, exit 0. kb adds no rule of its own: what it sends is what
 * was typed (ARCHITECTURE §1, interfaces only).
 */
class KbRestCommandsTest {

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

    static Stream<Case> cases() {
        return KbCommandCases.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void the_command_sends_its_request_and_prints_the_answer(Case command) {
        server.answer(command.method(), command.path(), 200, command.answer());

        Result result = server.kb(workDir, command.args().toArray(String[]::new));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        ApiStub.Request request = server.requests().getFirst();
        assertThat(server.requests()).hasSize(1);
        assertThat(request.method()).isEqualTo(command.method());
        assertThat(request.rawPath()).isEqualTo(command.path());
        assertThat(request.rawQuery()).isEqualTo(command.query());
        for (Pattern field : command.body()) {
            assertThat(request.body()).containsPattern(field);
        }
        assertThat(result.stdout()).contains(command.printed());
    }

    /** ADR-0025 decision 13: the server renders; kb asks for Markdown and prints it unchanged. */
    @Test
    void show_asks_for_markdown_and_prints_it_as_it_came() {
        server.answer("GET", "/api/v1/entities/ornek-app-01", 200, KbCommandCases.MARKDOWN);

        Result result = server.kb(workDir, "show", "ornek-app-01");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo(KbCommandCases.MARKDOWN);
        assertThat(server.requests().getFirst().headers().get("Accept"))
                .isEqualTo(List.of("text/markdown"));
    }

    /**
     * H6 review: kb show --json prints the server's JSON view, which holds the fact and relation
     * ids kb fact close and kb rel close take, as it came, minus control characters.
     */
    @Test
    void show_json_asks_for_json_and_prints_it_without_control_characters() {
        String json = KbCommandCases.contract("entity-answer.json");
        server.answer(
                "GET", "/api/v1/entities/ornek-app-01", 200, json.replace("Uygulama", "‮Uygulama"));

        Result result = server.kb(workDir, "show", "ornek-app-01", "--json");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo(json);
        assertThat(server.requests().getFirst().headers().get("Accept"))
                .isEqualTo(List.of("application/json"));
    }

    /** An id is one path segment: a slash or a question mark in it cannot reach another route. */
    @Test
    void an_id_is_sent_as_one_path_segment() {
        Result result = server.kb(workDir, "archive", "kbtest/a?b c");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(server.requests().getFirst().rawPath())
                .isEqualTo("/api/v1/entities/kbtest%2Fa%3Fb%20c/archive");
    }

    /** Invariant 2: the list never prints a value, even when an answer carried one. */
    @Test
    void secret_list_prints_no_value_even_if_the_answer_had_one() {
        String value = "kbtest-secret-value-must-not-print";
        server.answer(
                "GET",
                "/api/v1/secrets",
                200,
                KbCommandCases.contract("secrets-answer.json")
                        .replace(
                                "\"hasValue\": true",
                                "\"hasValue\": true, \"value\": \"" + value + "\""));

        Result result = server.kb(workDir, "secret", "list");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("ornek-lb/prod/panel-admin").doesNotContain(value);
        assertThat(server.requests().getFirst().rawQuery()).isEmpty();
    }

    /**
     * Options left out are not sent, so the server's defaults hold (search limit 8, ADR-0017); a
     * reserved character in the query stays in its value.
     */
    @Test
    void search_sends_only_the_options_given() {
        server.answer(
                "GET", "/api/v1/search", 200, KbCommandCases.contract("search-answer-empty.json"));

        Result result = server.kb(workDir, "search", "a&x=1 b#c");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("no matches");
        assertThat(server.requests().getFirst().rawQuery()).isEqualTo("q=a%26x%3D1%20b%23c");
    }
}
