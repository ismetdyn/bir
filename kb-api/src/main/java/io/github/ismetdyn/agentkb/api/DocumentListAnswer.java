package io.github.ismetdyn.agentkb.api;

import java.util.List;

/** {@code GET /api/v1/documents?limit=}: newest first; {@code more} if the limit cut the list. */
public record DocumentListAnswer(List<DocumentAnswer> documents, boolean more) {

    public DocumentListAnswer {
        documents = List.copyOf(documents);
    }
}
