package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Source;
import org.jspecify.annotations.Nullable;

/**
 * A normative change for the user to accept (SPEC §7 rule 6, docs/MCP-TOOLS.md kb_propose). {@code
 * target} is the id or alias of the record it changes. There is no reason field: the why goes into
 * {@code evidence} (ADR-0022 decision 31). {@code episode} as in {@link WriteRequest}.
 */
public record ProposalRequest(
        Source by,
        String kind,
        @Nullable String target,
        String content,
        @Nullable String evidence,
        @Nullable String episode) {

    /** A proposal of no known episode. */
    public ProposalRequest(
            Source by,
            String kind,
            @Nullable String target,
            String content,
            @Nullable String evidence) {
        this(by, kind, target, content, evidence, null);
    }
}
