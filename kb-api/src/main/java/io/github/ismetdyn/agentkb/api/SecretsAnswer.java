package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code GET /api/v1/secrets?prefix=&limit=}: the secrets by id, never a value (invariant 2), and
 * how many more there were past the limit; {@code hasValue} says whether this machine holds one.
 */
public record SecretsAnswer(List<Secret> secrets, int more) {

    public SecretsAnswer {
        secrets = List.copyOf(secrets);
    }

    /** {@code placeholder} is what kb run takes: {@code kb:secret/<id>}. */
    public record Secret(
            String id, String name, String env, String placeholder, boolean hasValue) {}
}
