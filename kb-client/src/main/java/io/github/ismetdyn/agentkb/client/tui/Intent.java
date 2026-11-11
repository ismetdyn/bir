package io.github.ismetdyn.agentkb.client.tui;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What TuiState asks of the world after a key or an answer: leave, or one REST call (TuiLoop makes
 * it off the render thread). TuiState itself never calls the server, so it is tested without one.
 */
sealed interface Intent {

    /** A write a form sent; {@code form} is that form's id, so only it hears the answer. */
    sealed interface FormWrite extends Intent {
        long form();
    }

    record Quit() implements Intent {}

    record LoadSchema() implements Intent {}

    /**
     * {@code GET /entities} for an empty query, else {@code GET /search}; only the answer of the
     * latest {@code seq} is shown. A debounced one waits for the typing to pause.
     */
    record LoadList(long seq, String query, @Nullable String type, boolean debounced)
            implements Intent {}

    /** The JSON view of the entity, for the preview and the detail. */
    record Open(String id) implements Intent {}

    record Edit(String id, List<String> aliases, String summary, long form) implements FormWrite {
        public Edit {
            aliases = List.copyOf(aliases);
        }
    }

    record SetFact(String id, String key, String value, @Nullable String env, long form)
            implements FormWrite {}

    record AddRelation(String id, String type, String target, long form) implements FormWrite {}

    record Archive(String id) implements Intent {}

    record Unarchive(String id) implements Intent {}
}
