package io.github.ismetdyn.agentkb.server.write;

import org.jspecify.annotations.Nullable;

/**
 * The answer of {@code kb sync} (docs/CLIENTS.md §2).
 *
 * @param commit the commit this sync made; null if nothing changed
 * @param pushed whether the remote now holds the local branch; false without a remote
 * @param nodes node rows of the snapshot written
 * @param edges edge rows of the snapshot written
 * @param conflicts ties and disputed values the merge found, each with a conflict signal
 */
public record SyncResult(
        @Nullable String commit, boolean pushed, int nodes, int edges, int conflicts) {}
