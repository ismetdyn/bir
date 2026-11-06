package io.github.ismetdyn.agentkb.api;

/**
 * {@code POST /api/v1/restore}: the snapshot's {@code nodes} and {@code edges}, the local Secrets
 * and Signals that stayed, the signal edges dropped because the snapshot lacks their other end, and
 * the secret values the snapshot carried and that were not loaded (ADR-0022 decision 38).
 */
public record RestoreAnswer(
        int nodes,
        int edges,
        int keptSecrets,
        int keptSignals,
        int droppedEdges,
        int droppedSecretValues) {}
