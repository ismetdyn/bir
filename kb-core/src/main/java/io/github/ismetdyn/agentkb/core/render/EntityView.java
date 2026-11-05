package io.github.ismetdyn.agentkb.core.render;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entity as {@link MarkdownView} renders it (SPEC §6.6). {@code facts} and {@code relations}
 * hold current and closed ones alike: nothing is hidden, the view sorts them into sections.
 */
public record EntityView(
        String id,
        String type,
        String status,
        @Nullable String summary,
        List<String> aliases,
        List<FactView> facts,
        List<RelationView> relations,
        List<SecretView> secrets) {

    public EntityView {
        aliases = List.copyOf(aliases);
        facts = List.copyOf(facts);
        relations = List.copyOf(relations);
        secrets = List.copyOf(secrets);
    }
}
