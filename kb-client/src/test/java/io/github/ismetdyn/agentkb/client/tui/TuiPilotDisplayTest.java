package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.PilotHarness.stub;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * What kb tui draws of server text (review 6): a tab or a line break is a space wherever it is
 * shown, the edit form included (the value sent stays as it came), and a wide (CJK) summary is cut
 * by its cells (TamboUI ends a cut text with "..."), so the panel's border stays where it is.
 */
class TuiPilotDisplayTest {

    private static final String CJK = "サーバーの概要".repeat(20);

    private static String entity(String summary, String value) {
        return "{\"id\": \"ornek-app-01\", \"type\": \"Server\", \"status\": \"current\","
                + " \"summary\": \""
                + summary
                + "\", \"aliases\": [\"a\\tb\"],"
                + " \"facts\": [{\"id\": \"F1\", \"key\": \"role\", \"value\": \""
                + value
                + "\","
                + " \"env\": \"prod\", \"source\": \"user\", \"status\": \"ok\","
                + " \"validFrom\": \"2026-05-10T09:00:00Z\", \"observedAt\": \"2026-05-10T09:00:00Z\","
                + " \"validTo\": null, \"target\": null, \"origin\": null}],"
                + " \"relations\": [], \"secrets\": []}";
    }

    @Test
    void a_tab_in_the_detail_is_a_space() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(entity("one\\ttwo", "web\\tdb")))) {
            tui.awaitScreen("one two");

            assertThat(tui.text()).contains("role @prod  web db").contains("aliases: a b");
            assertThat(tui.text()).doesNotContain("\t");
        }
    }

    @Test
    void the_edit_form_shows_a_multi_line_summary_on_one_line() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(entity("first\\nsecond", "web")))) {
            tui.awaitScreen("first second");

            tui.pilot().press('e');

            tui.awaitScreen("summary: first second");
            tui.awaitScreen("alias: a b");
            tui.pilot().press(KeyCode.ESCAPE);
        }
    }

    @Test
    void a_wide_summary_is_cut_by_cells_and_keeps_the_border() throws Exception {
        try (PilotHarness tui = PilotHarness.on(stub(entity(CJK, "web")))) {
            tui.awaitScreen("サーバーの概要");

            String line =
                    Arrays.stream(tui.text().split("\n"))
                            .filter(row -> row.contains("サーバーの概要"))
                            .findFirst()
                            .orElseThrow();
            assertThat(line).contains("...").endsWith("│");
            assertThat(line.split("│", -1)).hasSize(5);
        }
    }
}
