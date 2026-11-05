package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.model.Source;
import org.jspecify.annotations.Nullable;

/**
 * A normative change for the user to accept (SPEC §7 rule 6, docs/MCP-TOOLS.md kb_propose). {@code
 * target} is the id or alias of the record it changes. There is no reason field: the why goes into
 * {@code evidence} (ADR-0022 decision 31).
 */
public record ProposalRequest(
        Source by,
        String kind,
        @Nullable String target,
        String content,
        @Nullable String evidence) {}
