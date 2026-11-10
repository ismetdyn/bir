package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's edits of existing records (docs/CLIENTS.md §1, ADR-0025 decisions 10, 15 and 18):
 * {@code PATCH /api/v1/entities/{id} {aliases?, summary?}}, {@code POST /api/v1/facts/{id}/close},
 * {@code POST /api/v1/relations/{id}/close} and {@code POST /api/v1/entities/{id}/archive} and
 * {@code …/unarchive}. Each is one batch through WriteService.edits(), which {@code kb revert
 * --batch} undoes. A refusal is 400 {@code {error, hint}} without a request value.
 */
@RestController
class EditController {

    record PatchRequest(@Nullable List<@Nullable String> aliases, @Nullable String summary) {}

    // Lead boundary limit (H6): an entity has a few names; each alias is an id, which WriteCheck
    // keeps to 200 characters.
    private static final int ALIAS_COUNT = 20;

    private final WriteService writes;

    EditController(WriteService writes) {
        this.writes = writes;
    }

    @PatchMapping("/api/v1/entities/{id}")
    EditAnswer edit(@PathVariable String id, @RequestBody PatchRequest request) {
        List<@Nullable String> aliases = request.aliases();
        if (aliases != null
                && (aliases.size() > ALIAS_COUNT || aliases.stream().anyMatch(Objects::isNull))) {
            throw new WriteRejectedException(
                    "more than " + ALIAS_COUNT + " aliases, or an empty one",
                    "Keep at most " + ALIAS_COUNT + " names the entity is known by, each as text.");
        }
        List<String> given =
                aliases == null ? null : aliases.stream().map(Objects::requireNonNull).toList();
        return ApiAnswers.of(writes.edits().editEntity(id, given, request.summary()));
    }

    @PostMapping("/api/v1/facts/{id}/close")
    EditAnswer closeFact(@PathVariable String id) {
        return ApiAnswers.of(writes.edits().closeFact(id));
    }

    @PostMapping("/api/v1/relations/{id}/close")
    EditAnswer closeRelation(@PathVariable String id) {
        return ApiAnswers.of(writes.edits().closeRelation(id));
    }

    @PostMapping("/api/v1/entities/{id}/archive")
    EditAnswer archive(@PathVariable String id) {
        return ApiAnswers.of(writes.edits().archive(id));
    }

    @PostMapping("/api/v1/entities/{id}/unarchive")
    EditAnswer unarchive(@PathVariable String id) {
        return ApiAnswers.of(writes.edits().unarchive(id));
    }
}
