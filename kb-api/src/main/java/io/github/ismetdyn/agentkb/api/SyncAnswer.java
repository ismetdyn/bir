package io.github.ismetdyn.agentkb.api;

import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/v1/sync}: the commit made, null if nothing changed; whether the remote now holds
 * the branch; the snapshot rows written; the conflicts the merge found.
 */
public record SyncAnswer(
        @Nullable String commit, boolean pushed, int nodes, int edges, int conflicts) {}
