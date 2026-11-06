package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.docs.UploadStore;
import io.github.ismetdyn.agentkb.server.docs.UploadStore.Answer;
import io.github.ismetdyn.agentkb.server.docs.UploadStore.Append;
import io.github.ismetdyn.agentkb.server.docs.UploadStore.Start;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resumable upload staging (ADR-0025 decision 1, docs/CLIENTS.md §1): {@code PUT
 * /api/v1/uploads/{sha}} + {@code Upload-Length} starts or resumes, {@code PATCH} with {@code
 * Upload-Offset} and an {@code application/offset+octet-stream} body of at most 1 MiB
 * (RequestBodyLimitFilter) appends, {@code DELETE} discards. Answers carry {@code Upload-Offset}; a
 * refusal is {@code {error, hint}} and never repeats a request value. The body is handed to
 * UploadStore as a stream, never read into the heap.
 */
@RestController
class UploadController {

    private static final String PATH = "/api/v1/uploads/{sha}";
    private static final String OFFSET_STREAM = "application/offset+octet-stream";
    private static final String UPLOAD_LENGTH = "Upload-Length";
    private static final String UPLOAD_OFFSET = "Upload-Offset";
    // 18 digits stay below Long.MAX_VALUE, so parsing cannot overflow.
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,18}");
    private static final String RESUME_HINT =
            "PUT the upload again with its Upload-Length to learn its Upload-Offset.";

    private final UploadStore store;

    UploadController(UploadStore store) {
        this.store = store;
    }

    @PutMapping(PATH)
    ResponseEntity<Map<String, String>> start(
            @PathVariable String sha,
            @RequestHeader(name = UPLOAD_LENGTH, required = false) @Nullable String length)
            throws IOException {
        if (!UploadStore.isSha256(sha)) {
            return badId();
        }
        if (length == null || !NUMBER.matcher(length).matches()) {
            return refuse(
                    HttpStatus.BAD_REQUEST,
                    "Upload-Length is missing or not a byte count",
                    "Send Upload-Length: <file size in bytes>.");
        }
        Answer<Start> answer = store.start(sha, Long.parseLong(length));
        return switch (answer.outcome()) {
            case CREATED -> withOffset(HttpStatus.CREATED, answer.offset());
            case EXISTING -> withOffset(HttpStatus.OK, answer.offset());
            case LENGTH_CONFLICT ->
                    refuse(
                            HttpStatus.CONFLICT,
                            "this upload has another Upload-Length",
                            "DELETE the upload to start it over with a new length.");
            case NO_SPACE ->
                    refuse(
                            HttpStatus.INSUFFICIENT_STORAGE,
                            "not enough free disk space for this upload",
                            "Free space in the data directory and retry.");
            case NEEDS_GIT_LFS ->
                    refuse(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "a file of this size needs git-lfs, which is not installed",
                            "Install git-lfs (kb doctor checks it) and retry.");
        };
    }

    @PatchMapping(path = PATH, consumes = OFFSET_STREAM)
    ResponseEntity<Map<String, String>> append(
            @PathVariable String sha,
            @RequestHeader(name = UPLOAD_OFFSET, required = false) @Nullable String offset,
            InputStream body)
            throws IOException {
        if (!UploadStore.isSha256(sha)) {
            return badId();
        }
        if (offset == null || !NUMBER.matcher(offset).matches()) {
            return refuse(
                    HttpStatus.BAD_REQUEST,
                    "Upload-Offset is missing or not a byte count",
                    RESUME_HINT);
        }
        Answer<Append> answer = store.append(sha, Long.parseLong(offset), body);
        return switch (answer.outcome()) {
            case APPENDED -> withOffset(HttpStatus.NO_CONTENT, answer.offset());
            case OFFSET_CONFLICT ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .header(UPLOAD_OFFSET, Long.toString(answer.offset()))
                            .body(
                                    error(
                                            "Upload-Offset is not the bytes received so far",
                                            "Send the part that starts at the Upload-Offset of"
                                                    + " this answer."));
            case BUSY ->
                    refuse(
                            HttpStatus.CONFLICT,
                            "another PATCH of this upload is in progress",
                            RESUME_HINT);
            case PAST_LENGTH ->
                    refuse(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "the part runs past the Upload-Length of this upload",
                            "Send only the remaining bytes.");
            case UNKNOWN ->
                    refuse(
                            HttpStatus.NOT_FOUND,
                            "no upload with this id",
                            "Start it with PUT and an Upload-Length first.");
        };
    }

    @DeleteMapping(PATH)
    ResponseEntity<Map<String, String>> discard(@PathVariable String sha) throws IOException {
        if (!UploadStore.isSha256(sha)) {
            return badId();
        }
        store.discard(sha);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Map<String, String>> withOffset(HttpStatus status, long offset) {
        return ResponseEntity.status(status).header(UPLOAD_OFFSET, Long.toString(offset)).build();
    }

    private static ResponseEntity<Map<String, String>> badId() {
        return refuse(
                HttpStatus.BAD_REQUEST,
                "an upload id is the lowercase hex sha256 of the file",
                "Use the 64-character sha256 of the file as the id.");
    }

    private static ResponseEntity<Map<String, String>> refuse(
            HttpStatus status, String error, String hint) {
        return ResponseEntity.status(status).body(error(error, hint));
    }

    private static Map<String, String> error(String error, String hint) {
        return Map.of("error", error, "hint", hint);
    }
}
