package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.APP;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.entity;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.api.EntityAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The detail lines: nothing hidden (invariant 12), secrets by name and placeholder only, server
 * text on one line.
 */
class EntityLinesTest {

    @Test
    void the_detail_shows_closed_facts_ended_relations_and_only_secret_placeholders() {
        List<String> lines =
                EntityLines.of(entity(APP, "current")).stream()
                        .map(EntityLines.Line::text)
                        .toList();

        assertThat(lines)
                .anyMatch(
                        line -> line.contains("192.0.2.20") && line.contains("[closed 2026-05-10]"))
                .anyMatch(line -> line.contains("BEHIND → ornek-lb") && line.contains("[ended"))
                .anyMatch(line -> line.contains("deploy-key @prod · kb:secret/" + APP));
    }

    @Test
    void a_line_break_in_server_text_stays_on_one_line() {
        EntityAnswer entity =
                new EntityAnswer(
                        APP,
                        "Server",
                        "current",
                        "first\nsecond",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());

        assertThat(EntityLines.of(entity))
                .extracting(EntityLines.Line::text)
                .contains("first second");
    }

    @Test
    void a_tab_in_server_text_is_a_space_on_screen() {
        EntityAnswer entity =
                new EntityAnswer(
                        APP,
                        "Server",
                        "current",
                        "a\tb\r\nc",
                        List.of("x\ty"),
                        List.of(),
                        List.of(),
                        List.of());

        assertThat(EntityLines.of(entity))
                .extracting(EntityLines.Line::text)
                .contains("a b c", "aliases: x y");
    }
}
