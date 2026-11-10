package io.github.ismetdyn.agentkb.core.render;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One Document as a reader sees it (SPEC §6.2 and §6.7). Snapshot rows are taken as they are
 * (invariant 11), so every property but the id may be missing; {@code path} is the absolute path of
 * the blob, null when the sha256 is no sha256 or there is no knowledge repo. {@code about} holds
 * the ids of its ABOUT targets.
 */
public record DocumentView(
        String id,
        @Nullable String name,
        @Nullable String sha256,
        @Nullable String mime,
        @Nullable Long size,
        @Nullable String origin,
        @Nullable String addedAt,
        @Nullable String path,
        List<String> about) {

    public DocumentView {
        about = List.copyOf(about);
    }

    /** A fact that came from the document (FROM), with the id of the entity that has it. */
    public record Derived(@Nullable String entity, FactView fact) {}
}
