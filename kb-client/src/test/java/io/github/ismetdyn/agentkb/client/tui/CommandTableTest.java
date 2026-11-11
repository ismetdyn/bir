package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.key;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.ready;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The single command table (tui-gui-references §1.3): help and palette come from it and a key and
 * its palette row do the same.
 */
class CommandTableTest {

    @Test
    void help_and_palette_come_from_the_one_command_table() {
        assertThat(Commands.ALL)
                .extracting(Command::id)
                .doesNotHaveDuplicates()
                .contains(
                        "search",
                        "open",
                        "edit",
                        "fact",
                        "relation",
                        "archive",
                        "unarchive",
                        "help",
                        "palette",
                        "quit");
        assertThat(Commands.ALL.stream().map(Command::key).filter(k -> !k.isEmpty()))
                .doesNotHaveDuplicates();

        TuiState state = ready();
        state.onKey(key(':'));
        assertThat(state.palette.rows()).containsExactlyElementsOf(Commands.ALL);
        type(state, "archi");
        assertThat(state.palette.rows())
                .extracting(Command::id)
                .containsExactly("archive", "unarchive");
    }

    @Test
    void a_blocked_palette_row_does_not_run_and_the_palette_stays() {
        TuiState state = ready();
        state.onKey(key(':'));
        type(state, "unarchive");

        assertThat(state.onKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.PALETTE);
        assertThat(state.mode.message()).isEqualTo("unarchive: not archived");
    }

    /**
     * Every command with a key that can run on a ready state; a blocked one differs on purpose (the
     * palette stays open), a_blocked_palette_row_does_not_run_and_the_palette_stays has it.
     */
    static Stream<Command> keyedCommands() {
        return Commands.ALL.stream()
                .filter(command -> !command.key().isEmpty())
                .filter(command -> command.blocked().apply(ready()) == null);
    }

    /** noteapp's rule: a key and its palette row do the same thing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("keyedCommands")
    void a_key_and_its_palette_row_do_the_same(Command command) {
        TuiState byKey = ready();
        TuiState byPalette = ready();

        List<Intent> keyed = byKey.onKey(keyOf(command));
        byPalette.onKey(key(':'));
        type(byPalette, command.id());
        assertThat(byPalette.palette.rows().get(0)).isEqualTo(command);
        List<Intent> paletted = byPalette.onKey(key(KeyCode.ENTER));

        assertThat(paletted).isEqualTo(keyed);
        assertThat(byPalette.mode.focus()).isEqualTo(byKey.mode.focus());
        assertThat(byPalette.forms.form() == null).isEqualTo(byKey.forms.form() == null);
        assertThat(byPalette.mode.message()).isEqualTo(byKey.mode.message());
    }

    private static KeyEvent keyOf(Command command) {
        return command.key().equals(Keys.ENTER)
                ? key(KeyCode.ENTER)
                : KeyEvent.ofChar(command.key().codePointAt(0));
    }
}
