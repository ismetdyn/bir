package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.PilotHarness.stub;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.contract;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The first screen of {@code kb tui} under TamboUI's Pilot (plan.md H1; since H8a the list and
 * detail screen, which replaced the hello screen of the spike): it names the product, q quits and
 * another key does not. TuiPilotTest has the screens' work; the real terminal is
 * KbTerminalScenarios'.
 */
class HelloScreenTest {

    @Test
    void the_first_screen_names_agentkb() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen("agentkb");
        }
    }

    @Test
    void q_quits() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen("agentkb");

            tui.pilot().press('q');

            tui.await(() -> !tui.isRunning(), "still running after q");
        }
    }

    @Test
    void another_key_does_not_quit() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(contract("entity-answer.json")))) {
            tui.awaitScreen("agentkb");

            tui.pilot().press('x');
            tui.pilot().pause();

            assertThat(tui.isRunning()).isTrue();
        }
    }
}
