package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.server.scan.ScannerService;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/scan {path}} (docs/CLIENTS.md §1, {@code kb scan}): the scanner reads only
 * under agentkb.scan.allowed-roots (invariant 9) and refuses any other path before it reads. The
 * scan writes as source scan with an Episode of its own, as kb_scan does (ADR-0023 decision 3). The
 * answer names placeholders, never a value (invariant 2).
 */
@RestController
class ScanController {

    record ScanRequest(@Nullable String path) {}

    private static final String PATH_HINT =
            "Send {\"path\": <absolute path of a git working copy under agentkb.scan.allowed-roots>}.";

    private final ScannerService scanner;

    ScanController(ScannerService scanner) {
        this.scanner = scanner;
    }

    @PostMapping("/api/v1/scan")
    ScanAnswer scan(@RequestBody ScanRequest request) {
        String path = request.path();
        if (path == null || path.isBlank()) {
            throw new WriteRejectedException("path is missing", PATH_HINT);
        }
        Path dir;
        try {
            dir = Path.of(path.strip());
        } catch (InvalidPathException e) {
            throw new WriteRejectedException("path is not a valid path", PATH_HINT);
        }
        return ApiAnswers.of(scanner.scan(dir));
    }
}
