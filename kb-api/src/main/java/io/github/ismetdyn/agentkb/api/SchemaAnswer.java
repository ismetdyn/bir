package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/schema}: the vocabulary of {@code <repo>/schema/*.yaml} as kb_schema reads it
 * (SPEC §6.5), so a form or a completion never copies it from the server.
 */
public record SchemaAnswer(
        List<EntityType> entityTypes, List<FactKey> factKeys, List<RelationType> relationTypes) {

    public SchemaAnswer {
        entityTypes = List.copyOf(entityTypes);
        factKeys = List.copyOf(factKeys);
        relationTypes = List.copyOf(relationTypes);
    }

    /**
     * {@code key} as a write takes it, {@code label} as the graph holds it; kb stale looks at
     * operational types.
     */
    public record EntityType(String key, String label, boolean operational) {}

    /**
     * A fact key and its rules: {@code multi} if a key and env hold more than one current value (a
     * text key always does); {@code validator} the form of a value; {@code allowed} its only
     * values, empty for any; {@code kind} text for free text; {@code target} if a value may point
     * to an entity.
     */
    public record FactKey(
            String key,
            boolean multi,
            @Nullable String validator,
            List<String> allowed,
            @Nullable String kind,
            boolean target) {

        public FactKey {
            allowed = List.copyOf(allowed);
        }
    }

    /**
     * A relation type between entities as the graph writes it, and the synonyms a write takes for
     * it.
     */
    public record RelationType(String type, String description, List<String> synonyms) {

        public RelationType {
            synonyms = List.copyOf(synonyms);
        }
    }
}
