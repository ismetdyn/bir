package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.docs.DocumentService;
import io.github.ismetdyn.agentkb.server.docs.DocumentService.Completion;
import io.github.ismetdyn.agentkb.server.docs.UploadStore;
import io.github.ismetdyn.agentkb.server.write.DocumentWrite;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/documents {sha256, name, about?, origin?, replaces?}} completes an upload
 * (ADR-0025 decisions 1–5, docs/CLIENTS.md §1): 201 {@code {id, path}} for a new document, 200 with
 * the existing one for known content. A refusal is {@code {error, hint}} and never repeats a
 * request value or the content: 400 for a bad request, name or origin (RestErrorHandler), 404 for
 * no upload, 409 for an incomplete one, 422 for bytes that do not hash to the id or hold a secret,
 * which names only the line.
 */
@RestController
class DocumentController {

    record DocumentRequest(
            @Nullable String sha256,
            @Nullable String name,
            @Nullable String about,
            @Nullable String origin,
            @Nullable String replaces) {}

    private static final String UPLOAD_AGAIN = "Upload the file again with PUT and PATCH.";

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping("/api/v1/documents")
    ResponseEntity<Map<String, String>> add(@RequestBody DocumentRequest request)
            throws IOException {
        if (request.sha256() == null || !UploadStore.isSha256(request.sha256())) {
            return refuse(
                    HttpStatus.BAD_REQUEST,
                    "sha256 is missing or not the lowercase hex sha256 of the file",
                    "Send the 64-character sha256 the upload was made under.");
        }
        Completion done =
                documents.complete(
                        new DocumentWrite(
                                request.sha256(),
                                request.name(),
                                request.about(),
                                request.origin(),
                                request.replaces()));
        return switch (done.outcome()) {
            case CREATED -> added(HttpStatus.CREATED, done);
            case EXISTING -> added(HttpStatus.OK, done);
            case UNKNOWN ->
                    refuse(
                            HttpStatus.NOT_FOUND,
                            "no upload and no stored file with this sha256",
                            "Upload the file with PUT and PATCH first.");
            case INCOMPLETE ->
                    refuse(
                            HttpStatus.CONFLICT,
                            "the upload is not complete",
                            "PUT the upload again to learn its Upload-Offset, send the rest, then"
                                    + " complete it.");
            case HASH_MISMATCH ->
                    refuse(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "the uploaded bytes do not have this sha256; the upload was removed",
                            UPLOAD_AGAIN);
            case SECRET ->
                    refuse(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "line "
                                    + done.line()
                                    + " looks like it holds a secret value; the upload was"
                                    + " removed",
                            "Remove the value from the file, store it with kb secret set and"
                                    + " refer to it as kb:secret/<id>; then add the file again.");
        };
    }

    private static ResponseEntity<Map<String, String>> added(HttpStatus status, Completion done) {
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "id",
                                Objects.requireNonNull(done.id()),
                                "path",
                                Objects.requireNonNull(done.path())));
    }

    private static ResponseEntity<Map<String, String>> refuse(
            HttpStatus status, String error, String hint) {
        return ResponseEntity.status(status).body(Map.of("error", error, "hint", hint));
    }
}
