package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.server.mcp.worker.WriteTools.FactArg;
import io.github.ismetdyn.agentkb.server.mcp.worker.WriteTools.RelationArg;
import io.github.ismetdyn.agentkb.server.mcp.worker.WriteTools.SourceArg;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The kb_remember arguments as WriteRequest inputs: each fact and relation is marked unverified
 * when heard evidence does not hold what it says (ADR-0022 decisions 29 and 30).
 */
final class RememberInputs {

    private RememberInputs() {}

    static List<FactInput> facts(@Nullable List<FactArg> facts, @Nullable String heard) {
        return facts == null ? List.of() : facts.stream().map(fact -> fact(fact, heard)).toList();
    }

    static List<RelationInput> relations(
            @Nullable List<RelationArg> relations, @Nullable String heard) {
        return relations == null
                ? List.of()
                : relations.stream().map(relation -> relation(relation, heard)).toList();
    }

    static @Nullable String evidence(SourceArg source) {
        return source.evidence() == null || source.evidence().isBlank()
                ? source.ref()
                : source.evidence();
    }

    /**
     * ADR-0022 decision 29: heard evidence makes a value user_stated only if it holds that value
     * word for word; the message that holds the evidence then holds the value too.
     */
    private static boolean lacks(@Nullable String heard, @Nullable String value) {
        return heard != null && value != null && !PromptRing.holds(heard, value.strip());
    }

    private static FactInput fact(FactArg fact, @Nullable String heard) {
        return new FactInput(
                fact.key(),
                fact.value(),
                fact.env(),
                fact.target(),
                // Decision 30: the target is part of the value, so the evidence must hold it too.
                lacks(heard, fact.value()) || lacks(heard, fact.target()));
    }

    /** A relation has no value; its target is what the user must have said. */
    private static RelationInput relation(RelationArg relation, @Nullable String heard) {
        return new RelationInput(
                relation.type(),
                relation.target(),
                relation.env(),
                lacks(heard, relation.target()));
    }
}
