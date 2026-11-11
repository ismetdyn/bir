package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.APP;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.DB;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.entity;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.key;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.loadList;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.ready;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure model of {@code kb tui} (plan.md H8a, tui-gui-references §1.3–1.5): keys in, state and
 * intents out, no terminal and no server; here the list, the search and the moves. TuiWriteTest has
 * the writes, CommandTableTest the table, TuiPilotTest the same model end to end.
 */
class TuiStateTest {

    @Test
    void start_loads_the_schema_and_browses_every_entity() {
        List<Intent> intents = new TuiState().start();

        assertThat(intents)
                .containsExactly(new Intent.LoadSchema(), new Intent.LoadList(1, "", null, false));
    }

    @Test
    void q_in_the_list_quits() {
        assertThat(ready().onKey(key('q'))).containsExactly(new Intent.Quit());
    }

    @Test
    void ctrl_c_quits_from_a_text_field_too() {
        TuiState state = ready();
        state.onKey(key('/'));

        assertThat(state.onKey(KeyEvent.ofChar('c', KeyModifiers.CTRL)))
                .containsExactly(new Intent.Quit());
    }

    /** The most common TUI bug (noteapp): a shortcut fires while the user types. */
    @Test
    void letters_typed_into_the_search_are_text_not_shortcuts() {
        TuiState state = ready();
        state.onKey(key('/'));

        List<Intent> intents = type(state, "qedarı");

        assertThat(intents).doesNotContain(new Intent.Quit());
        assertThat(intents).allMatch(Intent.LoadList.class::isInstance);
        assertThat(state.mode.focus()).isEqualTo(Focus.SEARCH);
        assertThat(state.listing.query()).isEqualTo("qedarı");
    }

    @Test
    void typing_searches_debounced_and_only_the_latest_answer_is_shown() {
        TuiState state = ready();
        state.onKey(key('/'));
        Intent.LoadList first = loadList(type(state, "o"));
        Intent.LoadList second = loadList(type(state, "r"));

        assertThat(second).isEqualTo(new Intent.LoadList(first.seq() + 1, "or", null, true));
        SearchAnswer late =
                new SearchAnswer(
                        List.of(new SearchAnswer.Hit("stale", "Server", "current", false, null)),
                        0);
        assertThat(state.listLoaded(first.seq(), late)).isEmpty();
        assertThat(state.listing.shown().rows())
                .extracting(Listing.Row::id)
                .containsExactly(APP, DB);

        SearchAnswer latest =
                new SearchAnswer(
                        List.of(
                                new SearchAnswer.Hit(DB, "Database", "history", true, "old"),
                                new SearchAnswer.Hit("sig", "Signal", "raw", false, "raw text")),
                        3);
        assertThat(state.listLoaded(second.seq(), latest)).containsExactly(new Intent.Open(DB));
        assertThat(state.listing.shown().rows())
                .extracting(Listing.Row::id, Listing.Row::mark, Listing.Row::faded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DB, "history, archived", true),
                        org.assertj.core.groups.Tuple.tuple("sig", "raw", true));
        assertThat(state.listing.shown().more()).isEqualTo(3);
    }

    @Test
    void escape_clears_the_search_first_then_leaves_it() {
        TuiState state = ready();
        state.onKey(key('/'));
        type(state, "or");

        Intent.LoadList cleared = loadList(state.onKey(key(KeyCode.ESCAPE)));

        assertThat(cleared.query()).isEmpty();
        assertThat(cleared.debounced()).isFalse();
        assertThat(state.mode.focus()).isEqualTo(Focus.SEARCH);
        assertThat(state.onKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
    }

    @Test
    void moving_the_selection_previews_the_record_and_drops_an_older_answer() {
        TuiState state = ready();

        assertThat(state.onKey(key('j'))).containsExactly(new Intent.Open(DB));
        state.entityLoaded(APP, entity(APP, "current"));
        assertThat(state.detail.entity()).isNull();

        state.entityLoaded(DB, entity(DB, "archived"));
        assertThat(state.detail.entity()).isNotNull();
        assertThat(state.onKey(key(KeyCode.UP))).containsExactly(new Intent.Open(APP));
    }

    @Test
    void enter_opens_the_detail_and_escape_goes_back() {
        TuiState state = ready();

        state.onKey(key(KeyCode.ENTER));
        assertThat(state.mode.focus()).isEqualTo(Focus.DETAIL);
        state.onKey(key(KeyCode.ESCAPE));
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
    }

    @Test
    void t_browses_by_the_next_entity_type_of_the_schema() {
        TuiState state = ready();

        assertThat(loadList(state.onKey(key('t'))).type()).isEqualTo("server");
        assertThat(loadList(state.onKey(key('t'))).type()).isEqualTo("database");
        assertThat(loadList(state.onKey(key('t'))).type()).isNull();
    }

    @Test
    void pasted_text_goes_into_the_field_on_one_line_without_controls() {
        TuiState state = ready();
        state.onKey(key('/'));

        assertThat(loadList(state.onPaste("ornek\napp\u001b[2J\u202eevil\tİ"))).isNotNull();
        assertThat(state.listing.query()).isEqualTo("ornek app[2Jevil İ");
    }
}
