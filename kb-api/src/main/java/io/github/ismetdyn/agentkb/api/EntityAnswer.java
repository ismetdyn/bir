package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/entities/{id}} as JSON (ADR-0025 decision 13): every fact and relation,
 * current and closed alike (invariant 12), with the ids {@code kb fact close} and {@code kb rel
 * close} take, and the entity's secrets by name and placeholder. A secret value is never here.
 */
public record EntityAnswer(
        String id,
        String type,
        String status,
        @Nullable String summary,
        List<String> aliases,
        List<Fact> facts,
        List<Relation> relations,
        List<Secret> secrets) {

    public EntityAnswer {
        aliases = List.copyOf(aliases);
        facts = List.copyOf(facts);
        relations = List.copyOf(relations);
        secrets = List.copyOf(secrets);
    }

    /** Times are ISO-8601; a closed fact has {@code validTo}; {@code target} is a port's entity. */
    public record Fact(
            String id,
            String key,
            String value,
            String env,
            String source,
            String status,
            String validFrom,
            String observedAt,
            @Nullable String validTo,
            @Nullable String target,
            @Nullable String origin) {}

    /**
     * Seen from this entity: {@code outgoing} if it starts here; an ended one has validTo. {@code
     * id} is null only for an imported edge that came without one.
     */
    public record Relation(
            @Nullable String id,
            String type,
            boolean outgoing,
            String other,
            @Nullable String env,
            String source,
            String validFrom,
            String observedAt,
            @Nullable String validTo) {}

    /** {@code placeholder} is what kb run takes: {@code kb:secret/<id>}. */
    public record Secret(String id, String name, String env, String placeholder) {}
}
