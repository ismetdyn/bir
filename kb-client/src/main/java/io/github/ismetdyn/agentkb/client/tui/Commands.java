package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.client.tui.Mode.Focus;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * The single command table (tui-gui-references §1.3): the keys of the list and the detail, the
 * {@code :} palette and the {@code ?} help all read it, so a key and its palette row do the same
 * (TuiStateTest). Its order is the help's. Keys outside it (arrows, j/k, PgUp/PgDn, Esc, Ctrl+C)
 * only move or leave.
 */
final class Commands {

    static final List<Command> ALL =
            List.of(
                    new Command("search", "Search as you type", "/", go(Focus.SEARCH), s -> null),
                    new Command(
                            "open",
                            "Open the detail",
                            Keys.ENTER,
                            go(Focus.DETAIL),
                            s -> s.listing.selectedId() == null ? Detail.NO_RECORD : null),
                    new Command(
                            "type",
                            "Browse the next entity type",
                            "t",
                            s -> List.of(s.listing.nextType(s.forms.schema())),
                            s -> s.forms.schema() == null ? "the schema is not loaded" : null),
                    new Command(
                            "edit",
                            "Edit aliases and summary",
                            "e",
                            s -> form(s, id -> FormTemplates.edit(id, s.detail.entity())),
                            Commands::needsEntity),
                    new Command(
                            "fact",
                            "Add a fact",
                            "a",
                            s ->
                                    form(
                                            s,
                                            id ->
                                                    FormTemplates.fact(
                                                            id,
                                                            s.detail.entity().id(),
                                                            s.forms.schema())),
                            Commands::needsEntity),
                    new Command(
                            "relation",
                            "Add a relation",
                            "r",
                            s ->
                                    form(
                                            s,
                                            id ->
                                                    FormTemplates.relation(
                                                            id,
                                                            s.detail.entity().id(),
                                                            s.forms.schema())),
                            Commands::needsEntity),
                    new Command(
                            "archive",
                            "Archive the record",
                            "d",
                            s -> {
                                s.forms.ask(s.detail.entity().id());
                                s.mode.overlay(Focus.CONFIRM);
                                return List.of();
                            },
                            s -> s.detail.isArchived() ? "archived already" : needsEntity(s)),
                    new Command(
                            "unarchive",
                            "Unarchive the record",
                            "u",
                            s -> List.of(new Intent.Unarchive(s.detail.entity().id())),
                            s ->
                                    needsEntity(s) != null || s.detail.isArchived()
                                            ? needsEntity(s)
                                            : "not archived"),
                    new Command(
                            "reload",
                            "Reload the schema and the list",
                            "",
                            s -> List.of(new Intent.LoadSchema(), s.listing.load(false)),
                            s -> null),
                    new Command("help", "Show the keys", "?", overlay(Focus.HELP), s -> null),
                    new Command(
                            "palette",
                            "Command palette",
                            ":",
                            s -> {
                                s.palette.reset();
                                s.mode.overlay(Focus.PALETTE);
                                return List.of();
                            },
                            s -> null),
                    new Command("quit", "Quit", "q", s -> List.of(new Intent.Quit()), s -> null));

    private Commands() {}

    /** The command bound to {@code token} (Keys.token), if any. */
    static @Nullable Command find(String token) {
        return token.isEmpty()
                ? null
                : ALL.stream().filter(c -> c.key().equals(token)).findFirst().orElse(null);
    }

    private static Function<TuiState, List<Intent>> go(Focus focus) {
        return s -> {
            s.mode.go(focus);
            return List.of();
        };
    }

    private static Function<TuiState, List<Intent>> overlay(Focus dialog) {
        return s -> {
            s.mode.overlay(dialog);
            return List.of();
        };
    }

    private static List<Intent> form(TuiState s, LongFunction<Form> template) {
        s.forms.open(template);
        s.mode.overlay(Focus.FORM);
        return List.of();
    }

    private static @Nullable String needsEntity(TuiState s) {
        return s.detail.blocked(s.listing.selectedId());
    }
}
