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
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The archive question (plan.md H8a, review 1 and 7): it starts on Cancel, names the record it was
 * opened for and archives that one even if the list moves meanwhile; only ←/→ and y/n change the
 * choice.
 */
class ArchiveQuestionTest {

    @Test
    void archive_asks_first_and_enter_alone_cancels() {
        TuiState state = ready();

        assertThat(state.onKey(key('d'))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.CONFIRM);
        assertThat(state.forms.question()).isEqualTo(new Forms.Question(APP, false));
        assertThat(state.onKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);

        state.onKey(key('d'));
        state.onKey(key(KeyCode.RIGHT));
        assertThat(state.onKey(key(KeyCode.ENTER))).containsExactly(new Intent.Archive(APP));
    }

    @Test
    void escape_cancels_the_archive_question() {
        TuiState state = ready();
        state.onKey(key('d'));
        state.onKey(key(KeyCode.RIGHT));

        assertThat(state.onKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
        assertThat(state.forms.question()).isNull();
    }

    @Test
    void tab_does_not_change_the_choice_but_arrows_and_y_n_do() {
        TuiState state = ready();
        state.onKey(key('d'));

        state.onKey(key(KeyCode.TAB));
        assertThat(state.forms.question().archive()).isFalse();
        state.onKey(key('y'));
        assertThat(state.forms.question().archive()).isTrue();
        state.onKey(key('n'));
        assertThat(state.forms.question().archive()).isFalse();
        state.onKey(key(KeyCode.RIGHT));
        assertThat(state.forms.question().archive()).isTrue();
        state.onKey(key(KeyCode.LEFT));
        assertThat(state.forms.question().archive()).isFalse();
    }

    /** CWE-367: the answer is about the record the question named, whatever the list did since. */
    @Test
    void a_list_that_moves_while_asking_still_archives_the_record_asked_for() {
        TuiState state = ready();
        state.onKey(key('d'));
        long reload = loadList(state.written(new Intent.Unarchive(DB), "B0", 0)).seq();
        EntityListAnswer moved =
                new EntityListAnswer(
                        List.of(
                                new EntityListAnswer.Entity(
                                        DB, "Database", "current", null, "2026-11-10T08:00:00Z")),
                        0);
        assertThat(state.listLoaded(reload, moved)).containsExactly(new Intent.Open(DB));
        assertThat(state.detail.entity()).isNull();

        state.onKey(key(KeyCode.RIGHT));

        assertThat(state.onKey(key(KeyCode.ENTER))).containsExactly(new Intent.Archive(APP));
    }

    @Test
    void archive_is_blocked_on_an_archived_record_and_unarchive_runs() {
        TuiState state = ready();
        state.onKey(key('j'));
        state.entityLoaded(DB, entity(DB, "archived"));

        assertThat(state.onKey(key('d'))).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.LIST);
        assertThat(state.mode.message()).isEqualTo("archive: archived already");
        assertThat(state.onKey(key('u'))).containsExactly(new Intent.Unarchive(DB));
    }

    @Test
    void a_letter_in_the_question_is_no_command() {
        TuiState state = ready();
        state.onKey(key('d'));

        assertThat(type(state, "qe")).isEmpty();
        assertThat(state.mode.focus()).isEqualTo(Focus.CONFIRM);
        assertThat(state.forms.question().id()).isEqualTo(APP);
    }
}
