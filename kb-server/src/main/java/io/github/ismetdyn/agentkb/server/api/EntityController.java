package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.WriteAnswer;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import io.github.ismetdyn.agentkb.server.write.WriteResult;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's new knowledge (docs/CLIENTS.md §1, ADR-0025 decision 9): {@code POST /api/v1/entities
 * {id, type, facts?, relations?}} (201 new, 200 known), {@code POST /api/v1/entities/{id}/facts
 * {facts}} and {@code POST …/{id}/relations {relations}}. Every write is Source.USER, one batch per
 * request and without an Episode, through WriteService (invariant 1), which refuses a write with
 * nothing in it: a new entity comes with at least one fact or relation. Edits of existing records
 * are EditController's. A refusal is 400 {@code {error, hint}} without a request value
 * (RestErrorHandler).
 */
@RestController
class EntityController {

    record CreateRequest(
            @Nullable String id,
            @Nullable String type,
            @Nullable List<@Nullable FactItem> facts,
            @Nullable List<@Nullable RelationItem> relations) {}

    record FactsRequest(@Nullable List<@Nullable FactItem> facts) {}

    record FactItem(
            @Nullable String key,
            @Nullable String value,
            @Nullable String env,
            @Nullable String target) {

        FactInput input() {
            return new FactInput(key, value, env, target);
        }
    }

    record RelationsRequest(@Nullable List<@Nullable RelationItem> relations) {}

    record RelationItem(@Nullable String type, @Nullable String target, @Nullable String env) {

        RelationInput input() {
            return new RelationInput(type, target, env);
        }
    }

    private static final String ITEM_HINT =
            "Send {\"facts\": [{\"key\", \"value\", \"env\"?, \"target\"?}]} or {\"relations\":"
                    + " [{\"type\", \"target\", \"env\"?}]}.";

    private final WriteService writes;

    EntityController(WriteService writes) {
        this.writes = writes;
    }

    @PostMapping("/api/v1/entities")
    ResponseEntity<WriteAnswer> create(@RequestBody CreateRequest request) {
        if (request.id() == null || request.type() == null) {
            throw new WriteRejectedException(
                    "id or type is missing",
                    "Send {\"id\": <new id>, \"type\": <entity type>, \"facts\": [...]}.");
        }
        WriteResult result =
                write(
                        request.id(),
                        request.type(),
                        items(request.facts(), true).stream().map(FactItem::input).toList(),
                        items(request.relations(), true).stream()
                                .map(RelationItem::input)
                                .toList());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiAnswers.of(result));
    }

    @PostMapping("/api/v1/entities/{id}/facts")
    WriteAnswer facts(@PathVariable String id, @RequestBody FactsRequest request) {
        List<FactInput> facts =
                items(request.facts(), false).stream().map(FactItem::input).toList();
        return ApiAnswers.of(write(id, null, facts, List.of()));
    }

    @PostMapping("/api/v1/entities/{id}/relations")
    WriteAnswer relations(@PathVariable String id, @RequestBody RelationsRequest request) {
        List<RelationInput> relations =
                items(request.relations(), false).stream().map(RelationItem::input).toList();
        return ApiAnswers.of(write(id, null, List.of(), relations));
    }

    /** ADR-0025 decision 9: the user's, one batch of its own, no Episode. */
    private WriteResult write(
            String entity,
            @Nullable String type,
            List<FactInput> facts,
            List<RelationInput> relations) {
        return writes.write(
                new WriteRequest(
                        Source.USER,
                        entity,
                        type,
                        facts,
                        relations,
                        null,
                        null,
                        null,
                        Instant.now()));
    }

    /** An absent list is empty only where it is {@code optional}; an empty item is refused. */
    private static <T> List<T> items(@Nullable List<@Nullable T> items, boolean optional) {
        if (items == null && optional) {
            return List.of();
        }
        if (items == null || items.stream().anyMatch(Objects::isNull)) {
            throw new WriteRejectedException(
                    "the list is missing or holds an empty item", ITEM_HINT);
        }
        return items.stream().map(Objects::requireNonNull).toList();
    }
}
