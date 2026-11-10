package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.ListQueries;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.ActivityFilter;
import io.github.ismetdyn.agentkb.server.write.GraphEdits.Resolution;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the user reviews (docs/CLIENTS.md §1): {@code GET /api/v1/conflicts}, {@code POST
 * /api/v1/conflicts/{id}/resolve {resolution: accept-new|keep-old}} (ADR-0025 decision 11), {@code
 * GET /api/v1/proposals}, {@code POST /api/v1/proposals/{id}/accept} and {@code …/reject {note?}}
 * (decisions 12 and 18), and {@code GET /api/v1/activity?source=&batch=&since=}. Lists take {@code
 * limit} (50 by default, 500 at most) and say how many more there were. A review is the user's
 * edit, one batch (WriteService.edits()). A refusal is 400 {@code {error, hint}} without the
 * request value.
 */
@RestController
class ReviewController {

    record ResolveRequest(@Nullable String resolution) {}

    record RejectRequest(@Nullable String note) {}

    private static final Map<String, Resolution> RESOLUTIONS =
            Map.of("accept-new", Resolution.ACCEPT_NEW, "keep-old", Resolution.KEEP_OLD);
    private static final List<String> SOURCES =
            Arrays.stream(Source.values()).map(source -> Ids.normalize(source.name())).toList();

    private final ListQueries lists;
    private final WriteService writes;

    ReviewController(ListQueries lists, WriteService writes) {
        this.lists = lists;
        this.writes = writes;
    }

    @GetMapping("/api/v1/conflicts")
    ConflictsAnswer conflicts(@RequestParam(required = false) @Nullable String limit) {
        return ListAnswers.conflicts(lists.conflicts(), RequestChecks.listLimit(limit));
    }

    @PostMapping("/api/v1/conflicts/{id}/resolve")
    EditAnswer resolve(@PathVariable String id, @RequestBody ResolveRequest request) {
        Resolution resolution =
                request.resolution() == null ? null : RESOLUTIONS.get(request.resolution());
        if (resolution == null) {
            throw new WriteRejectedException(
                    "resolution is missing or unknown",
                    "Send {\"resolution\": \"accept-new\"} or {\"resolution\": \"keep-old\"}.");
        }
        return ApiAnswers.of(writes.edits().resolveConflict(id, resolution));
    }

    @GetMapping("/api/v1/proposals")
    ProposalsAnswer proposals(@RequestParam(required = false) @Nullable String limit) {
        return ListAnswers.proposals(lists.proposals(), RequestChecks.listLimit(limit));
    }

    @PostMapping("/api/v1/proposals/{id}/accept")
    EditAnswer accept(@PathVariable String id) {
        return ApiAnswers.of(writes.edits().acceptProposal(id));
    }

    @PostMapping("/api/v1/proposals/{id}/reject")
    EditAnswer reject(
            @PathVariable String id,
            @RequestBody(required = false) @Nullable RejectRequest request) {
        return ApiAnswers.of(
                writes.edits().rejectProposal(id, request == null ? null : request.note()));
    }

    @GetMapping("/api/v1/activity")
    ActivityAnswer activity(
            @RequestParam(required = false) @Nullable String source,
            @RequestParam(required = false) @Nullable String batch,
            @RequestParam(required = false) @Nullable String since,
            @RequestParam(required = false) @Nullable String limit) {
        if (source != null && !SOURCES.contains(source)) {
            throw new WriteRejectedException(
                    "source is not a source", "Give one of " + String.join(", ", SOURCES) + ".");
        }
        ActivityFilter filter =
                new ActivityFilter(
                        source, RequestChecks.batch(batch), RequestChecks.time(since, "since"));
        return ListAnswers.activity(lists.activity(filter), RequestChecks.listLimit(limit));
    }
}
