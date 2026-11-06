package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A new write from MCP or REST (ARCHITECTURE §5). {@code entity} is an id or alias; {@code type} is
 * needed only to create an entity. Without {@code batch} the write gets a batch of its own. {@code
 * episode} is stamped on what the write opens if it names an Episode (ADR-0023); otherwise the
 * records have none. Nothing here is trusted: {@link WriteService#write} checks it all before it
 * writes.
 */
public record WriteRequest(
        Source source,
        String entity,
        @Nullable String type,
        List<FactInput> facts,
        List<RelationInput> relations,
        @Nullable String evidence,
        @Nullable String batch,
        @Nullable String episode,
        Instant at) {

    public WriteRequest {
        facts = facts == null ? List.of() : List.copyOf(facts);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /** A write of no known episode. */
    public WriteRequest(
            Source source,
            String entity,
            @Nullable String type,
            List<FactInput> facts,
            List<RelationInput> relations,
            @Nullable String evidence,
            @Nullable String batch,
            Instant at) {
        this(source, entity, type, facts, relations, evidence, batch, null, at);
    }

    /**
     * {@code target} is an entity id or alias for keys that take one (port). {@code unverified}
     * stores the fact as {@code agent} whatever the request's source: user_stated evidence that
     * lacks this value (SPEC §7 rule 7).
     */
    public record FactInput(
            String key,
            String value,
            @Nullable String env,
            @Nullable String target,
            boolean unverified) {

        public FactInput(String key, String value, @Nullable String env, @Nullable String target) {
            this(key, value, env, target, false);
        }
    }

    /**
     * {@code type} is a vocabulary type or synonym in any case; {@code target} an id or alias.
     * {@code unverified} as for a fact, with the target as the written value.
     */
    public record RelationInput(
            String type, String target, @Nullable String env, boolean unverified) {

        public RelationInput(String type, String target, @Nullable String env) {
            this(type, target, env, false);
        }
    }
}
