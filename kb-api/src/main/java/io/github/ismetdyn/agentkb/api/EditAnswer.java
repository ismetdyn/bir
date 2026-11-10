package io.github.ismetdyn.agentkb.api;

/**
 * A user edit of an existing record (close, PATCH, archive, conflict resolution, proposal review):
 * its one batch, which {@code kb revert --batch} takes.
 */
public record EditAnswer(String batch) {}
