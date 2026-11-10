package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.DocumentAnswer;
import io.github.ismetdyn.agentkb.api.DocumentListAnswer;
import io.github.ismetdyn.agentkb.core.render.DocumentView;
import io.github.ismetdyn.agentkb.server.graph.DocumentQueries;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/documents?limit=} and {@code GET /api/v1/documents/{id}} (docs/CLIENTS.md §1,
 * plan.md H7): the list newest first, at most {@code limit} (default 50, at most 500, as the
 * activity list), and one document with the absolute path of its blob, which the user or agent
 * opens with their own tools. A bad limit is 400, an unknown id 404, both {@code {error, hint}}
 * that never repeat the request.
 */
@RestController
class DocumentReadController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    // At most three digits: no number that overflows an int gets as far as parseInt.
    private static final Pattern LIMIT = Pattern.compile("\\d{1,3}");

    private final DocumentQueries documents;

    DocumentReadController(DocumentQueries documents) {
        this.documents = documents;
    }

    @GetMapping("/api/v1/documents")
    DocumentListAnswer list(@RequestParam(required = false) @Nullable String limit) {
        int shown = limit(limit);
        // One more than shown tells whether the limit cut the list.
        List<DocumentView> found = documents.documents(shown + 1);
        return new DocumentListAnswer(
                found.stream().limit(shown).map(DocumentReadController::answer).toList(),
                found.size() > shown);
    }

    @GetMapping("/api/v1/documents/{id}")
    ResponseEntity<?> document(@PathVariable String id) {
        return documents
                .document(id)
                .<ResponseEntity<?>>map(found -> ResponseEntity.ok(answer(found)))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(
                                                Map.of(
                                                        "error",
                                                        "no document with this id",
                                                        "hint",
                                                        "GET /api/v1/documents (kb doc list)"
                                                                + " lists the documents with"
                                                                + " their ids, doc:<12 hex"
                                                                + " digits>.")));
    }

    private static int limit(@Nullable String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        int value = LIMIT.matcher(limit).matches() ? Integer.parseInt(limit) : 0;
        if (value < 1 || value > MAX_LIMIT) {
            throw new WriteRejectedException(
                    "limit is not a number from 1 to " + MAX_LIMIT,
                    "Leave limit out for " + DEFAULT_LIMIT + ", or give 1 to " + MAX_LIMIT + ".");
        }
        return value;
    }

    private static DocumentAnswer answer(DocumentView document) {
        return new DocumentAnswer(
                document.id(),
                document.name(),
                document.sha256(),
                document.mime(),
                document.size(),
                document.origin(),
                document.addedAt(),
                document.path(),
                document.about());
    }
}
