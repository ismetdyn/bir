package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import io.github.ismetdyn.agentkb.server.hooks.MemoryImport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckService;
import io.github.ismetdyn.agentkb.server.write.SnapshotRestore;
import io.github.ismetdyn.agentkb.server.write.SyncService;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F2 maintenance endpoints (docs/CLIENTS.md §1, ADR-0022 decision 5): {@code POST
 * /api/v1/revert {batch}}, {@code POST /api/v1/restore {at?}}, {@code POST /api/v1/sync} (body
 * ignored), {@code POST /api/v1/signals/imported-memories {memories}} and {@code GET
 * /api/v1/check}. JSON in and out, no token: OriginGuard keeps browsers out (ADR-0020). A refusal
 * is 400 {@code {error, hint}} (RestErrorHandler); a batch or commit name beyond the length and
 * charset limits is refused before anything looks it up, and the refusal never repeats it. Each
 * answer is a kb-api record (ApiAnswers maps the domain result; ADR-0025 decision 6).
 */
@RestController
class MaintenanceController {

    record RevertRequest(@Nullable String batch) {}

    record RestoreRequest(@Nullable String at) {}

    record ImportRequest(@Nullable List<@Nullable ImportedMemory> memories) {}

    record ImportedMemory(@Nullable String source, @Nullable String path, @Nullable String text) {}

    private static final Set<String> MEMORY_SOURCES = Set.of("claude-code", "codex");
    // PATH_MAX on Linux; a memory path under HOME is far shorter.
    private static final int PATH_LIMIT = 4_096;
    private static final String IMPORT_HINT =
            "Send {\"memories\": [{\"source\": \"claude-code\" or \"codex\", \"path\": <path under"
                    + " HOME>, \"text\": <content>}]}, as kb import-memories does.";

    // A batch is a ULID (26) the server made; 64 leaves room for a test or tool prefix.
    private static final int BATCH_LIMIT = 64;
    private static final Pattern BATCH = Pattern.compile("[A-Za-z0-9._:-]{0," + BATCH_LIMIT + "}");
    // A sha-256 commit hash is 64; a ref name or rev expression rarely passes 100.
    private static final int AT_LIMIT = 256;

    private final WriteService writes;
    private final SnapshotRestore restore;
    private final CheckService check;
    private final SyncService sync;
    private final MemoryImport memoryImport;

    MaintenanceController(
            WriteService writes,
            SnapshotRestore restore,
            CheckService check,
            SyncService sync,
            MemoryImport memoryImport) {
        this.writes = writes;
        this.restore = restore;
        this.check = check;
        this.sync = sync;
        this.memoryImport = memoryImport;
    }

    @PostMapping("/api/v1/sync")
    SyncAnswer sync() {
        return ApiAnswers.of(sync.sync());
    }

    @PostMapping("/api/v1/revert")
    RevertAnswer revert(@RequestBody RevertRequest request) {
        String batch = request.batch();
        if (batch != null && !BATCH.matcher(batch).matches()) {
            throw new WriteRejectedException(
                    "the batch is not a batch id (at most "
                            + BATCH_LIMIT
                            + " letters, digits, '.', '_', ':' or '-')",
                    "Give the batch id a write returned, e.g. kb revert --batch <id>.");
        }
        return ApiAnswers.of(writes.revert(batch));
    }

    @PostMapping("/api/v1/restore")
    RestoreAnswer restore(@RequestBody RestoreRequest request) {
        String at = request.at();
        if (at != null
                && (at.length() > AT_LIMIT || at.chars().anyMatch(Character::isISOControl))) {
            throw new WriteRejectedException(
                    "the commit name is longer than "
                            + AT_LIMIT
                            + " characters or holds a control character",
                    "Give a commit of the knowledge repository (git log in it lists them), or"
                            + " none for HEAD.");
        }
        return ApiAnswers.of(restore.restore(at));
    }

    /**
     * Every memory is checked before any becomes a signal, so a refused body opens nothing; the
     * refusal never repeats a memory's text.
     */
    @PostMapping("/api/v1/signals/imported-memories")
    MemoryImportAnswer importMemories(@RequestBody ImportRequest request) {
        List<@Nullable ImportedMemory> memories = request.memories();
        if (memories == null) {
            throw new WriteRejectedException("the body has no memories list", IMPORT_HINT);
        }
        List<MemoryImport.Memory> checked = new ArrayList<>(memories.size());
        for (ImportedMemory memory : memories) {
            checked.add(
                    checked(memory)
                            .orElseThrow(
                                    () ->
                                            new WriteRejectedException(
                                                    "memory "
                                                            + checked.size()
                                                            + " is not {source: claude-code|codex,"
                                                            + " path, text} or its path is longer"
                                                            + " than "
                                                            + PATH_LIMIT
                                                            + " characters or holds a control"
                                                            + " character",
                                                    IMPORT_HINT)));
        }
        return ApiAnswers.of(memoryImport.importAll(checked));
    }

    private static Optional<MemoryImport.Memory> checked(@Nullable ImportedMemory memory) {
        if (memory == null
                || memory.source() == null
                || !MEMORY_SOURCES.contains(memory.source())
                || memory.text() == null) {
            return Optional.empty();
        }
        String path = memory.path();
        if (path == null
                || path.isBlank()
                || path.length() > PATH_LIMIT
                || path.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(new MemoryImport.Memory(memory.source(), path, memory.text()));
    }

    @GetMapping("/api/v1/check")
    CheckAnswer check() {
        return ApiAnswers.of(check.check());
    }
}
