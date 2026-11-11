package io.github.ismetdyn.agentkb.client.tui;

import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.APP;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.DB;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.key;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.loadList;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.ready;
import static io.github.ismetdyn.agentkb.client.tui.TuiSamples.type;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The forms of {@code kb tui} in the pure model: a form sends what was typed and shows the server's
 * refusal as it came (plan.md H8a); an answer reaches only the form that sent the write (review 2);
 * aliases are one per field, so one with a comma is sent back whole (review 8).
 */
class TuiWriteTest {

    @Test
    void a_write_command_without_a_loaded_record_says_why() {
        TuiState state = new TuiState();
        state.start();

        assertThat(state.onKey(key('a'))).isEmpty();
        assertThat(state.forms.form()).isNull();
        assertThat(state.mode.message()).isEqualTo("fact: no record selected");
        assertThat(state.mode.isError()).isTrue();
    }

    @Test
    void the_fact_form_picks_keys_from_the_schema_and_sends_what_was_typed() {
        TuiState state = ready();
        state.onKey(key('a'));
        long form = state.forms.form().id();
        type(state, "en");

        state.onKey(key(KeyCode.DOWN));
        assertThat(state.forms.form().fields().get(0).value()).isEqualTo("engine");
        state.onKey(key(KeyCode.DOWN));
        assertThat(state.forms.form().fields().get(0).value()).isEqualTo("env");
        state.onKey(key(KeyCode.UP));
        assertThat(state.forms.form().fields().get(0).value()).isEqualTo("engine");

        state.onKey(key(KeyCode.TAB));
        type(state, "not-in-allowed İğ");
        assertThat(state.onKey(key(KeyCode.ENTER)))
                .containsExactly(
                        new Intent.SetFact(APP, "engine", "not-in-allowed İğ", null, form));
    }

    /** No client-side rule: the server's refusal is shown in the form, which stays open. */
    @Test
    void a_refused_write_keeps_the_form_open_with_the_server_message() {
        TuiState state = ready();
        state.onKey(key('a'));
        type(state, "ip");
        state.onKey(key(KeyCode.TAB));
        type(state, "x");
        state.onKey(key(KeyCode.TAB));
        type(state, "prod");
        Intent write = state.onKey(key(KeyCode.ENTER)).get(0);
        assertThat(write)
                .isEqualTo(new Intent.SetFact(APP, "ip", "x", "prod", state.forms.form().id()));

        state.failed(write, "the server answered 422: invalid ip; give an IPv4 address", false);

        assertThat(state.mode.focus()).isEqualTo(Focus.FORM);
        assertThat(state.forms.form().error())
                .isEqualTo("the server answered 422: invalid ip; give an IPv4 address");
        assertThat(state.forms.form().fields().get(1).value()).isEqualTo("x");
    }

    @Test
    void a_saved_write_closes_the_form_names_the_batch_and_reloads() {
        TuiState state = ready();
        state.onKey(key('r'));
        state.onKey(key(KeyCode.DOWN));
        state.onKey(key(KeyCode.TAB));
        type(state, DB);
        Intent write = state.onKey(key(KeyCode.ENTER)).get(0);
        assertThat(write)
                .isEqualTo(new Intent.AddRelation(APP, "RUNS", DB, state.forms.form().id()));

        List<Intent> next = state.written(write, "B1", 1);

        assertThat(state.forms.form()).isNull();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
        assertThat(state.mode.message())
                .isEqualTo(
                        "relation added to "
                                + APP
                                + "; batch B1 (kb revert --batch B1 undoes it); 1 waiting as a"
                                + " conflict");
        assertThat(loadList(next).query()).isEmpty();
    }

    /** Review 2: a late answer of a form that was left does not close or mark the open one. */
    @Test
    void an_answer_reaches_only_the_form_that_sent_the_write() {
        TuiState state = ready();
        state.onKey(key('a'));
        type(state, "ip");
        Intent left = state.onKey(key(KeyCode.ENTER)).get(0);
        state.onKey(key(KeyCode.ESCAPE));
        state.onKey(key('r'));
        long open = state.forms.form().id();

        state.failed(left, "the server answered 422: invalid ip", false);
        assertThat(state.forms.form().id()).isEqualTo(open);
        assertThat(state.forms.form().error()).isNull();
        assertThat(state.mode.message()).isEqualTo("the server answered 422: invalid ip");

        state.written(left, "B2", 0);
        assertThat(state.forms.form().id()).isEqualTo(open);
        assertThat(state.mode.focus()).isEqualTo(Focus.FORM);
    }

    @Test
    void the_edit_form_has_one_field_per_alias_and_sends_them_with_a_new_one() {
        TuiState state = ready();
        state.onKey(key('e'));
        long form = state.forms.form().id();
        assertThat(state.forms.form().fields())
                .extracting(Field::name, Field::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("alias", "app01"),
                        org.assertj.core.groups.Tuple.tuple("new alias", ""),
                        org.assertj.core.groups.Tuple.tuple("summary", "app server"));
        state.onKey(key(KeyCode.TAB));
        type(state, "app-İ");
        state.onKey(key(KeyCode.TAB));
        state.onKey(key(KeyCode.BACKSPACE));

        assertThat(state.onKey(key(KeyCode.ENTER)))
                .containsExactly(
                        new Intent.Edit(APP, List.of("app01", "app-İ"), "app serve", form));
    }

    /**
     * Review 8: the server takes a comma or a line break in an alias or summary; Enter keeps them.
     */
    @Test
    void an_unchanged_edit_sends_back_an_alias_with_a_comma_and_a_multi_line_summary() {
        TuiState state = ready();
        EntityAnswer tricky =
                new EntityAnswer(
                        APP,
                        "Server",
                        "current",
                        "first\nsecond",
                        List.of("a, b", "c"),
                        List.of(),
                        List.of(),
                        List.of());
        state.entityLoaded(APP, tricky);
        state.onKey(key('e'));
        long form = state.forms.form().id();

        assertThat(state.onKey(key(KeyCode.ENTER)))
                .containsExactly(new Intent.Edit(APP, List.of("a, b", "c"), "first\nsecond", form));
    }

    @Test
    void clearing_an_alias_field_drops_that_alias() {
        TuiState state = ready();
        state.onKey(key('e'));
        long form = state.forms.form().id();
        for (int i = 0; i < "app01".length(); i++) {
            state.onKey(key(KeyCode.BACKSPACE));
        }

        assertThat(state.onKey(key(KeyCode.ENTER)))
                .containsExactly(new Intent.Edit(APP, List.of(), "app server", form));
    }

    @Test
    void escape_leaves_a_form_without_writing() {
        TuiState state = ready();
        state.onKey(key('e'));

        assertThat(state.onKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(state.forms.form()).isNull();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
    }

    @Test
    void the_server_being_unreachable_shows_in_the_status() {
        TuiState state = new TuiState();
        Intent load = state.start().get(1);

        state.failed(load, "cannot reach the agentkb server", true);

        assertThat(state.mode.isServerUp()).isFalse();
        assertThat(state.mode.message()).isEqualTo("cannot reach the agentkb server");
        assertThat(state.mode.isError()).isTrue();
    }
}
