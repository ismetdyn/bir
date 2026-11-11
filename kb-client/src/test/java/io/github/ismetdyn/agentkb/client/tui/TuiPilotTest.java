package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.PilotHarness.APP;
import static io.github.ismetdyn.agentkb.client.tui.PilotHarness.stub;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.contract;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import io.github.ismetdyn.agentkb.client.ApiStub;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code kb tui} end to end under TamboUI's Pilot (plan.md H8a), through PilotHarness. */
class TuiPilotTest {

    private static final String PLACEHOLDER = "kb:secret/ornek-app-01/prod/deploy-ssh-key";

    @Test
    void start_lists_the_records_marked_and_previews_the_first_without_secret_values()
            throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen(PLACEHOLDER);

            assertThat(tui.text())
                    .contains("agentkb ●")
                    .contains("ornek-app-00  Server  archived")
                    .contains("192.0.2.21")
                    .contains("[conflict]  [closed 2026-05-10]")
                    .contains("RUNS ← ornek-proje")
                    .contains("[ended 2026-05-10]")
                    .contains("deploy-ssh-key @prod");
            assertThat(tui.server().requests())
                    .extracting(ApiStub.Request::rawPath)
                    .noneMatch(path -> path.startsWith("/api/v1/secrets"));
        }
    }

    @Test
    void typing_after_slash_searches_once_after_a_pause() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen(PLACEHOLDER);

            type(tui.pilot(), "/orı");

            tui.awaitScreen("01KBTESTSIGNAL");
            assertThat(tui.text()).contains("/ orı").contains("history, archived");
            assertThat(tui.server().requests())
                    .filteredOn(request -> request.rawPath().equals("/api/v1/search"))
                    .extracting(ApiStub.Request::rawQuery)
                    .containsExactly("q=or%C4%B1&limit=20");
        }
    }

    /** No client-side rule: the server's refusal and hint appear in the open form. */
    @Test
    void a_refused_fact_shows_the_server_error_in_the_form() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen(PLACEHOLDER);
            Pilot pilot = tui.pilot();

            pilot.press('a');
            tui.awaitScreen("Add a fact to ornek-app-01");
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            tui.awaitScreen("one of: oracle, postgresql");
            pilot.press(KeyCode.TAB);
            type(pilot, "mysql");
            pilot.press(KeyCode.ENTER);

            // The refusal wraps in the 72-column dialog; both halves are there, whole.
            tui.awaitScreen("│the server answered 422: invalid value for engine; one of: oracle,");
            assertThat(tui.text()).contains("│postgresql ").contains("value: mysql");
            assertThat(lastRequest(tui, "POST").body())
                    .contains("\"key\": \"engine\"")
                    .contains("\"value\": \"mysql\"");
        }
    }

    @Test
    void archive_asks_first_and_enter_alone_keeps_the_record() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen(PLACEHOLDER);
            Pilot pilot = tui.pilot();

            pilot.press('d');
            tui.awaitScreen("[ Cancel ]");
            pilot.press(KeyCode.ENTER);
            tui.await(() -> !tui.text().contains("[ Cancel ]"), "the dialog stayed");
            pilot.pause();
            assertThat(tui.server().requests())
                    .extracting(ApiStub.Request::method)
                    .doesNotContain("POST");

            pilot.press('d');
            pilot.press(KeyCode.RIGHT);
            pilot.press(KeyCode.ENTER);
            tui.awaitScreen("archived ornek-app-01; batch 01KBTESTBATCH0000000000000Z");
            assertThat(lastRequest(tui, "POST").rawPath()).isEqualTo(APP + "/archive");
        }
    }

    @Test
    void server_text_is_drawn_on_one_line_without_control_characters() throws Exception {
        String hostile =
                "{\"id\": \"ornek-app-01\", \"type\": \"Server\", \"status\": \"current\","
                        + " \"summary\": \"first\\nsecond \\u001b[2J\\u202eevil\", \"aliases\": [],"
                        + " \"facts\": [], \"relations\": [], \"secrets\": []}";
        try (PilotHarness tui = PilotHarness.on(stub(hostile))) {
            tui.awaitScreen("first second [2Jevil");

            assertThat(tui.text()).doesNotContain("\u001b").doesNotContain("‮");
        }
    }

    @Test
    void help_lists_every_command_of_the_table_and_q_quits() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen(PLACEHOLDER);

            tui.pilot().press('?');
            tui.awaitScreen("Keys");
            for (Command command : Commands.ALL) {
                assertThat(tui.text()).contains(command.label() + "  (" + command.id() + ")");
            }
            tui.pilot().press(KeyCode.ESCAPE);
            tui.pilot().press('q');
            tui.await(() -> !tui.isRunning(), "still running after q");
        }
    }

    @Test
    void an_unreachable_server_is_shown_and_the_screen_stays() throws Exception {
        try (PilotHarness tui = PilotHarness.against("http://127.0.0.1:1")) {
            tui.awaitScreen("○ unreachable");

            assertThat(tui.text()).contains("cannot reach the agentkb server");
            assertThat(tui.isRunning()).isTrue();
        }
    }

    /** Review 4: a record the selection passed over while another was loading is not asked for. */
    @Test
    void a_record_passed_over_while_loading_is_not_requested() throws Exception {
        ApiStub slow =
                stub(contract("entity-answer.json")).delay("GET", APP, Duration.ofMillis(600));
        try (PilotHarness tui = PilotHarness.on(slow)) {
            tui.await(
                    () -> slow.requests().stream().anyMatch(r -> r.rawPath().equals(APP)),
                    "ornek-app-01 not asked for");

            tui.pilot().press('j');
            tui.pilot().press('j');

            tui.await(
                    () ->
                            slow.requests().stream()
                                    .anyMatch(
                                            r ->
                                                    r.rawPath()
                                                            .equals(
                                                                    "/api/v1/entities/ornek-app-00")),
                    "ornek-app-00 not asked for");
            assertThat(slow.requests())
                    .extracting(ApiStub.Request::rawPath)
                    .doesNotContain("/api/v1/entities/ornek-proje");
        }
    }

    private static void type(Pilot pilot, String text) {
        text.chars().forEach(c -> pilot.press((char) c));
    }

    private static ApiStub.Request lastRequest(PilotHarness tui, String method) {
        return tui.server().requests().stream()
                .filter(request -> request.method().equals(method))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no " + method + " request"));
    }
}
