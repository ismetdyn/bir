package io.github.ismetdyn.agentkb.server.scan;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * SPEC S1, ARCHITECTURE §11: after a SessionStart, the working copy of its {@code cwd} is scanned
 * in the background if it is under {@code agentkb.scan.allowed-roots} and its HEAD is not the
 * commit its Repo's last scan read ({@code Repo.commit}). Nothing of it runs on the hook's thread,
 * so a slow scan or a hanging git never costs the session its answer. Only exception classes are
 * logged (invariant 10).
 */
@Component
public class SessionScan implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SessionScan.class);
    private static final String SCANNED_AT =
            "MATCH (r:Repo {id: $id}) WHERE r.commit = $commit RETURN count(r) > 0 AS scanned";

    private final ScannerService scanner;
    private final EmbeddedNeo4jStore store;
    private final ExecutorService scans = Executors.newVirtualThreadPerTaskExecutor();
    // ponytail: keyed by cwd, so two sessions in different folders of one working copy can scan
    // it twice at once; key by the top level if that shows up.
    private final Set<Path> running = ConcurrentHashMap.newKeySet();

    public SessionScan(ScannerService scanner, EmbeddedNeo4jStore store) {
        this.scanner = scanner;
        this.store = store;
    }

    /**
     * Starts the check and scan of {@code cwd} apart and returns at once; never throws. The scan is
     * stamped with {@code episode}, the session's Episode (ADR-0023 decision 3), or with one of its
     * own if that is null.
     */
    public void afterSessionStart(String cwd, @Nullable String episode) {
        Path dir;
        try {
            dir = Path.of(cwd).normalize();
        } catch (InvalidPathException e) {
            return;
        }
        if (!dir.isAbsolute() || !running.add(dir)) {
            return;
        }
        try {
            scans.execute(() -> scan(dir, episode));
        } catch (RuntimeException e) {
            running.remove(dir);
            LOG.warn("event=session_scan_not_started error={}", e.getClass().getName());
        }
    }

    @Override
    public void destroy() {
        // A git that hangs is cut only after its timeout; the shutdown does not wait for it.
        scans.shutdownNow();
    }

    private void scan(Path dir, @Nullable String episode) {
        try {
            scanIfMoved(dir, episode);
        } catch (WriteRejectedException e) {
            // Outside the roots, no git working copy: the usual case, not a fault.
            LOG.debug("event=session_scan_skipped");
        } catch (RuntimeException e) {
            LOG.warn("event=session_scan_failed error={}", e.getClass().getName());
        } finally {
            running.remove(dir);
        }
    }

    /**
     * The working copy {@code dir} is in, scanned from its top level unless its Repo was last
     * scanned at the HEAD it has now. A working copy with no commit has no HEAD to compare and is
     * left to kb_scan. The roots are checked before git runs, as in {@link ScannerService#scan}.
     *
     * @throws WriteRejectedException if the scanner may not read there
     */
    private void scanIfMoved(Path dir, @Nullable String episode) {
        List<Path> roots = scanner.roots();
        LocalGit git = new LocalGit(ScannerService.allowed(dir, roots));
        Optional<Path> top = git.topLevel();
        if (top.isEmpty()) {
            return;
        }
        Path real = ScannerService.insideRoots(git, top.get(), roots);
        LocalGit atTop = new LocalGit(real);
        Optional<String> head = atTop.head();
        String repo =
                RemoteUrl.repoId(atTop.remote().map(RemoteUrl::normalize), RemoteUrl.folder(real));
        if (head.isPresent() && !isScannedAt(repo, head.get())) {
            scanner.scan(real, episode);
        }
    }

    private boolean isScannedAt(String repo, String head) {
        return store.read(
                tx -> {
                    try (Result result =
                            tx.execute(SCANNED_AT, Map.of("id", repo, "commit", head))) {
                        return (Boolean) result.next().get("scanned");
                    }
                });
    }
}
