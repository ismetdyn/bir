package io.github.ismetdyn.agentkb.api;

/**
 * {@code POST /api/v1/revert}: the batch, the records it opened and that were {@code deleted}, the
 * ones it closed and that were {@code reopened}, and the deleted Secrets that held a value, which
 * lived only on this machine.
 */
public record RevertAnswer(String batch, int deleted, int reopened, int deletedSecretValues) {}
