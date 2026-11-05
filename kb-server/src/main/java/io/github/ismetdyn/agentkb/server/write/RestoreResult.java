package io.github.ismetdyn.agentkb.server.write;

/**
 * What {@link WriteService#replaceAll} did (ADR-0022 decision 38): the snapshot's {@code nodes} and
 * {@code edges}, the local Secrets the snapshot lacks and that stayed ({@code keptSecrets}), the
 * Signals that stayed ({@code keptSignals}) and the signal edges that went because the snapshot
 * lacks their other end ({@code droppedEdges}). A kept Secret whose entity is gone stays without a
 * holder; kb check lists it as a local record. {@code droppedSecretValues} counts the snapshot rows
 * that carried a secret_value, which a snapshot never does (invariant 2): the value was not loaded.
 */
public record RestoreResult(
        int nodes,
        int edges,
        int keptSecrets,
        int keptSignals,
        int droppedEdges,
        int droppedSecretValues) {}
