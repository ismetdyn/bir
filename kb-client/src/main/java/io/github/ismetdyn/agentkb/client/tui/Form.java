package io.github.ismetdyn.agentkb.client.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * An open form (FormTemplates makes them). Enter sends what was typed; nothing is checked here, the
 * server's refusal ({@code error; hint}) is shown in the form as it came and the form stays open.
 * Its {@code id} goes with its writes, so a late answer cannot reach another form.
 */
final class Form {

    private final long id;
    private final String title;
    private final List<Field> fields;
    private final Function<List<Field>, Intent> submit;
    private int at;
    private @Nullable String error;

    Form(long id, String title, Function<List<Field>, Intent> submit, List<Field> fields) {
        this.id = id;
        this.title = title;
        this.submit = submit;
        this.fields = List.copyOf(fields);
    }

    long id() {
        return id;
    }

    String title() {
        return title;
    }

    List<Field> fields() {
        return fields;
    }

    /** The index of the field that has the keys. */
    int at() {
        return at;
    }

    @Nullable String error() {
        return error;
    }

    /** The server refused the write: its message stays until the next Enter. */
    void refuse(String message) {
        error = message;
    }

    /** Tab moves between fields, ↑↓ pick, Enter sends; Esc is the caller's. */
    @Nullable Intent onKey(KeyEvent key) {
        Field field = fields.get(at);
        if (key.isKey(KeyCode.ENTER)) {
            error = null;
            return submit.apply(fields);
        }
        if (key.isKey(KeyCode.TAB)) {
            at = Math.floorMod(at + (key.hasShift() ? -1 : 1), fields.size());
        } else if (key.isUp() || key.isDown()) {
            field.pick(key.isUp() ? -1 : 1);
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            field.type(Keys.dropLast(field.value()));
        } else if (!Keys.text(key).isEmpty()) {
            field.type(field.value() + Keys.text(key));
        }
        return null;
    }

    void paste(String line) {
        fields.get(at).type(fields.get(at).value() + line);
    }
}
