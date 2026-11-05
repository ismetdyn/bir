package io.github.ismetdyn.agentkb.core.render;

/**
 * A secret as a reader sees it. There is no value field on purpose (invariant 2): a view cannot
 * leak what it never holds.
 */
public record SecretView(String id, String name, String env) {}
