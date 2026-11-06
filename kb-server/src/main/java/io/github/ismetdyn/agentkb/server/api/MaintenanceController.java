package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.snapshot.CheckReport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckService;
import io.github.ismetdyn.agentkb.server.write.RestoreResult;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.SnapshotRestore;
import io.github.ismetdyn.agentkb.server.write.SyncResult;
import io.github.ismetdyn.agentkb.server.write.SyncService;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F2 maintenance endpoints (docs/CLIENTS.md §1, ADR-0022 decision 5): {@code POST
 * /api/v1/revert {batch}}, {@code POST /api/v1/restore {at?}}, {@code POST /api/v1/sync} (body
 * ignored) and {@code GET /api/v1/check}. JSON in and out, no token: OriginGuard keeps browsers out
 * (ADR-0020). A refusal is 400 {@code {error, hint}} (RestErrorHandler); a batch or commit name
 * beyond the length and charset limits is refused before anything looks it up, and the refusal
 * never repeats it.
 */
@RestController
class MaintenanceController {

    record RevertRequest(@Nullable String batch) {}

    record RestoreRequest(@Nullable String at) {}

    record CheckAnswer(boolean clean, List<CheckReport.Problem> problems) {}

    // A batch is a ULID (26) the server made; 64 leaves room for a test or tool prefix.
    private static final int BATCH_LIMIT = 64;
    private static final Pattern BATCH = Pattern.compile("[A-Za-z0-9._:-]{0," + BATCH_LIMIT + "}");
    // A sha-256 commit hash is 64; a ref name or rev expression rarely passes 100.
    private static final int AT_LIMIT = 256;

    private final WriteService writes;
    private final SnapshotRestore restore;
    private final CheckService check;
    private final SyncService sync;

    MaintenanceController(
            WriteService writes, SnapshotRestore restore, CheckService check, SyncService sync) {
        this.writes = writes;
        this.restore = restore;
        this.check = check;
        this.sync = sync;
    }

    @PostMapping("/api/v1/sync")
    SyncResult sync() {
        return sync.sync();
    }

    @PostMapping("/api/v1/revert")
    RevertResult revert(@RequestBody RevertRequest request) {
        String batch = request.batch();
        if (batch != null && !BATCH.matcher(batch).matches()) {
            throw new WriteRejectedException(
                    "the batch is not a batch id (at most "
                            + BATCH_LIMIT
                            + " letters, digits, '.', '_', ':' or '-')",
                    "Give the batch id a write returned, e.g. kb revert --batch <id>.");
        }
        return writes.revert(batch);
    }

    @PostMapping("/api/v1/restore")
    RestoreResult restore(@RequestBody RestoreRequest request) {
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
        return restore.restore(at);
    }

    @GetMapping("/api/v1/check")
    CheckAnswer check() {
        CheckReport report = check.check();
        return new CheckAnswer(report.clean(), report.problems());
    }
}
