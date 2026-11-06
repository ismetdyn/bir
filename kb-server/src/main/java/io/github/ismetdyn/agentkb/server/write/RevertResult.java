package io.github.ismetdyn.agentkb.server.write;

/**
 * What {@link GraphMaintenance#revert} did: {@code deleted} nodes and edges the batch opened,
 * {@code reopened} records it had closed. {@code deletedSecretValues} counts the deleted Secrets
 * that held a value, also one the user set after the batch; it lived only on this machine and is
 * gone.
 */
public record RevertResult(String batch, int deleted, int reopened, int deletedSecretValues) {}
