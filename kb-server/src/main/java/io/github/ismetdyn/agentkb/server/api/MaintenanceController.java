package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.snapshot.CheckReport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckService;
import io.github.ismetdyn.agentkb.server.write.RestoreResult;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.SnapshotRestore;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F2 maintenance endpoints (docs/CLIENTS.md §1, ADR-0022 decision 5): {@code POST
 * /api/v1/revert {batch}}, {@code POST /api/v1/restore {at?}} and {@code GET /api/v1/check}. JSON
 * in and out, no token: OriginGuard keeps browsers out (ADR-0020). A refusal is 400 {@code {error,
 * hint}} (RestErrorHandler).
 */
@RestController
class MaintenanceController {

    record RevertRequest(@Nullable String batch) {}

    record RestoreRequest(@Nullable String at) {}

    record CheckAnswer(boolean clean, List<CheckReport.Problem> problems) {}

    private final WriteService writes;
    private final SnapshotRestore restore;
    private final CheckService check;

    MaintenanceController(WriteService writes, SnapshotRestore restore, CheckService check) {
        this.writes = writes;
        this.restore = restore;
        this.check = check;
    }

    @PostMapping("/api/v1/revert")
    RevertResult revert(@RequestBody RevertRequest request) {
        return writes.revert(request.batch());
    }

    @PostMapping("/api/v1/restore")
    RestoreResult restore(@RequestBody RestoreRequest request) {
        return restore.restore(request.at());
    }

    @GetMapping("/api/v1/check")
    CheckAnswer check() {
        CheckReport report = check.check();
        return new CheckAnswer(report.clean(), report.problems());
    }
}
