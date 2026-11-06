package io.github.ismetdyn.agentkb.api;

/**
 * {@code POST /api/v1/signals/imported-memories}: the memories {@code received}, those that {@code
 * opened} a new signal, and those whose text was {@code truncated}.
 */
public record MemoryImportAnswer(int received, int opened, int truncated) {}
