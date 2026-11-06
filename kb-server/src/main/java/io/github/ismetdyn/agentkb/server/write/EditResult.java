package io.github.ismetdyn.agentkb.server.write;

/** What a user edit (GraphEdits) did: its one batch, which {@code kb revert --batch} takes. */
public record EditResult(String batch) {}
