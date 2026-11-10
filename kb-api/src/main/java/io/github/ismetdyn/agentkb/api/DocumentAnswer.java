package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/documents/{id}} and one entry of the list: the Document's metadata, the
 * absolute {@code path} of its blob and the ids it is {@code about}. A snapshot row from another
 * machine is taken as it is, so any property but the id may be null; {@code path} is null when the
 * sha256 is no sha256.
 */
public record DocumentAnswer(
        String id,
        @Nullable String name,
        @Nullable String sha256,
        @Nullable String mime,
        @Nullable Long size,
        @Nullable String origin,
        @Nullable String addedAt,
        @Nullable String path,
        List<String> about) {

    public DocumentAnswer {
        about = List.copyOf(about);
    }
}
