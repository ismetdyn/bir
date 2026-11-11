package io.github.ismetdyn.agentkb.client.tui;

import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * The schema the server gave ({@code GET /schema}; the client never copies it) and what is open on
 * top of the screen: a form, each with its own id, or the archive question.
 */
final class Forms {

    /** The archive question: the record it was opened for and whether "Archive" is chosen. */
    record Question(String id, boolean archive) {}

    private @Nullable SchemaAnswer schema;
    private @Nullable Form form;
    private @Nullable Question question;
    private long opened;

    @Nullable SchemaAnswer schema() {
        return schema;
    }

    @Nullable Form form() {
        return form;
    }

    @Nullable Question question() {
        return question;
    }

    /** Whether form {@code id} is the one open; an answer for another one is not its own. */
    boolean isOpen(long id) {
        return form != null && form.id() == id;
    }

    void learn(SchemaAnswer answer) {
        schema = answer;
    }

    /** Opens the form {@code template} makes with a new id. */
    void open(LongFunction<Form> template) {
        opened++;
        form = template.apply(opened);
    }

    /** Asks whether to archive {@code id}, with "Cancel" chosen. */
    void ask(String id) {
        question = new Question(id, false);
    }

    void choose(boolean archive) {
        question = new Question(question.id(), archive);
    }

    void close() {
        form = null;
        question = null;
    }
}
