package io.github.ismetdyn.agentkb.client.tui;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One row of the single command table (Commands.ALL): the key of the list and the detail, the
 * {@code :} palette and the {@code ?} help all come from it, so a key and its palette row do the
 * same thing. {@code id} is stable English; {@code key} is empty for a palette-only command; {@code
 * blocked} says why it cannot run now, null if it can.
 */
record Command(
        String id,
        String label,
        String key,
        Function<TuiState, List<Intent>> run,
        Function<TuiState, @Nullable String> blocked) {}
