package io.github.ismetdyn.agentkb.client.tui;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.DialogElement;
import dev.tamboui.toolkit.elements.TextElement;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The dialogs over the panels: a form (with the schema's choices and notes and the server's
 * refusal), the archive question (Cancel chosen first), the help and the palette, both from the
 * command table.
 */
final class Dialogs {

    private static final int WIDTH = 72;
    private static final int MARGIN = 2;
    private static final int BORDERS = 2;
    private static final int CHOICES_SHOWN = 6;
    private static final int KEY_COLUMN = 3;

    private Dialogs() {}

    /** The dialog of the focus, null if none. */
    static @Nullable Element over(TuiState state, int screenWidth) {
        int width = Math.max(1, Math.min(WIDTH, screenWidth - MARGIN));
        return switch (state.mode.focus()) {
            case FORM -> form(state.forms, width).width(width);
            case CONFIRM -> confirm(state).width(width);
            case HELP -> help().width(width);
            case PALETTE -> palette(state).width(width);
            default -> null;
        };
    }

    private static DialogElement form(Forms forms, int width) {
        Form form = forms.form();
        List<Element> lines = new ArrayList<>();
        for (int i = 0; i < form.fields().size(); i++) {
            Field field = form.fields().get(i);
            boolean current = i == form.at();
            TextElement line =
                    text(field.name()
                                    + ": "
                                    + Keys.flat(field.value())
                                    + (current ? TuiView.CURSOR : ""))
                            .ellipsis();
            lines.add(current ? line.bold() : line);
            if (current) {
                lines.addAll(choices(forms.schema(), field));
            }
        }
        if (form.error() != null) {
            for (String line : wrap(Keys.flat(form.error()), width - BORDERS)) {
                lines.add(text(line).red());
            }
        }
        return dialog(Keys.flat(form.title()), lines.toArray(Element[]::new)).rounded();
    }

    /** The schema's names that fit what was typed, and what the schema says of the chosen one. */
    private static List<Element> choices(@Nullable SchemaAnswer schema, Field field) {
        List<Element> lines = new ArrayList<>();
        List<String> candidates = field.candidates();
        if (!candidates.isEmpty()) {
            String shown =
                    String.join(
                            " ", candidates.subList(0, Math.min(CHOICES_SHOWN, candidates.size())));
            lines.add(
                    text("  " + shown + (candidates.size() > CHOICES_SHOWN ? " …" : ""))
                            .dim()
                            .ellipsis());
        }
        String note = schema == null ? null : note(schema, field);
        if (note != null) {
            lines.add(text("  " + Keys.flat(note)).dim().ellipsis());
        }
        return lines;
    }

    /** The server's own words on a fact key or a relation type; the client checks nothing. */
    private static @Nullable String note(SchemaAnswer schema, Field field) {
        if ("key".equals(field.name())) {
            return schema.factKeys().stream()
                    .filter(key -> key.key().equals(field.value()))
                    .map(Dialogs::factNote)
                    .findFirst()
                    .orElse(null);
        }
        return schema.relationTypes().stream()
                .filter(type -> "type".equals(field.name()) && type.type().equals(field.value()))
                .map(SchemaAnswer.RelationType::description)
                .findFirst()
                .orElse(null);
    }

    private static String factNote(SchemaAnswer.FactKey key) {
        return (key.multi() ? "several values" : "one value")
                + (key.validator() == null ? "" : " · form: " + key.validator())
                + (key.allowed().isEmpty() ? "" : " · one of: " + String.join(", ", key.allowed()))
                + (key.target() ? " · may name an entity" : "");
    }

    private static DialogElement confirm(TuiState state) {
        TextElement cancel = text("[ Cancel ]");
        TextElement archive = text("[ Archive ]");
        (state.forms.question().archive() ? archive : cancel).reversed();
        String id = Keys.flat(state.forms.question().id());
        return dialog(
                        "Archive",
                        text("Archive " + id + "?").ellipsis(),
                        text("Nothing is deleted; u unarchives it.").dim(),
                        row(cancel.length(cancel.content().length() + 2), archive.fill()))
                .rounded()
                .borderColor(Color.YELLOW);
    }

    private static DialogElement help() {
        List<Element> lines = new ArrayList<>();
        for (Command command : Commands.ALL) {
            lines.add(
                    text(pad(command.key())
                                    + command.label()
                                    + TuiView.GAP
                                    + "("
                                    + command.id()
                                    + ")")
                            .ellipsis());
        }
        lines.add(text(pad("") + "↑↓ j k move, PgUp PgDn page, Esc back, Ctrl+C quit").dim());
        return dialog("Keys", lines.toArray(Element[]::new)).rounded();
    }

    /** A row that cannot run now is dim with its reason. */
    private static DialogElement palette(TuiState state) {
        List<Element> lines = new ArrayList<>();
        lines.add(text(": " + state.palette.query() + TuiView.CURSOR).bold());
        List<Command> commands = state.palette.rows();
        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            String reason = command.blocked().apply(state);
            TextElement line =
                    text(pad(command.key())
                                    + command.label()
                                    + TuiView.GAP
                                    + command.id()
                                    + (reason == null ? "" : " (" + reason + ")"))
                            .ellipsis();
            if (reason != null) {
                line.dim();
            }
            lines.add(i == state.palette.at() ? line.reversed() : line);
        }
        return dialog("Command palette", lines.toArray(Element[]::new)).rounded();
    }

    /**
     * {@code text} in lines of at most {@code width} characters, broken at spaces; a dialog is as
     * high as its lines, so a long server refusal must be lines of its own to be seen whole.
     */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
            while (line.length() > width && width > 0) {
                lines.add(line.substring(0, width));
                line.delete(0, width);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String pad(String key) {
        return key + " ".repeat(Math.max(1, KEY_COLUMN - key.length()));
    }
}
