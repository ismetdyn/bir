package io.github.ismetdyn.agentkb.server.write;

import java.util.List;

/**
 * Whether {@link GraphMaintenance#revert} would undo a batch now, asked without writing: {@code
 * blockedBy} are the later batches that built on it and go first, by id. A batch without records or
 * one that accepted a schema proposal is not revertible and names none.
 */
public record RevertCheck(boolean revertible, List<String> blockedBy) {

    public RevertCheck {
        blockedBy = List.copyOf(blockedBy);
    }
}
