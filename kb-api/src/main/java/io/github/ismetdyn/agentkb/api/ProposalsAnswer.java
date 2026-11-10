package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/proposals}: open proposals first, then the reviewed ones, newest first; how
 * many more there were past the limit.
 */
public record ProposalsAnswer(List<Proposal> proposals, int more) {

    public ProposalsAnswer {
        proposals = List.copyOf(proposals);
    }

    /** {@code status} is open, accepted or rejected; {@code by} the source that proposed it. */
    public record Proposal(
            String id,
            String kind,
            String status,
            String by,
            @Nullable String target,
            String content,
            @Nullable String evidence,
            @Nullable String reviewNote) {}
}
