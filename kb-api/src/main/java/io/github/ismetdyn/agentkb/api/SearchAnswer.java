package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/search} and {@code GET /api/v1/stale}: hits in the order of the result list
 * (SPEC §6.3, invariant 12) — current first, then history, then raw — and how many more there were
 * past the limit. Nothing is left out for its time or status; a hit says what it is.
 */
public record SearchAnswer(List<Hit> hits, int more) {

    public SearchAnswer {
        hits = List.copyOf(hits);
    }

    /**
     * One record: {@code tier} is current, history or raw; {@code archived} marks an archived
     * entity; the snippet carries the marks of a fact (eski, ÇELİŞKİ, doğrulanmamış).
     */
    public record Hit(
            String id, String type, String tier, boolean archived, @Nullable String snippet) {}
}
