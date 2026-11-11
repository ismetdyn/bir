package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/activity/{batch}?limit=}: one batch as the activity list shows it, the records
 * it changed and whether {@code kb revert --batch} would undo it now. {@code blockedBy} names the
 * later batches to revert first; a batch that accepted a schema proposal is never revertible and
 * names none. {@code more} counts the changes past the limit.
 */
public record BatchAnswer(
        String batch,
        String at,
        List<String> sources,
        boolean revertible,
        List<String> blockedBy,
        List<Change> changes,
        int more) {

    public BatchAnswer {
        sources = List.copyOf(sources);
        blockedBy = List.copyOf(blockedBy);
        changes = List.copyOf(changes);
    }

    /**
     * One record the batch {@code change}d (opened, closed or revised): its {@code kind} (entity,
     * fact, relation, secret, …), the {@code entity} it belongs to, its {@code key} (fact key,
     * relation type, entity type, secret name; the fields a revision changed), {@code env}, a
     * {@code value} of one line cut as a search snippet (a relation's target; never a secret value)
     * and the {@code source} that wrote it.
     */
    public record Change(
            String change,
            String kind,
            @Nullable String id,
            @Nullable String entity,
            @Nullable String key,
            @Nullable String env,
            @Nullable String value,
            String source) {}
}
