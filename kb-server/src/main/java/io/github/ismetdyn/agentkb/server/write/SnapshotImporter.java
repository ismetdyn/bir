package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.neo4j.graphdb.Result;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * On start, an empty graph is loaded from {@code <agentkb.repo>/snapshot} (ARCHITECTURE §8). Runs
 * during bean initialisation, before the web server opens, so a broken snapshot stops the start and
 * no request ever sees a half-filled graph. Without {@code agentkb.repo} nothing is loaded. The
 * schema is ensured first, on every start.
 *
 * <p>Lives in {@code write}, not {@code snapshot}: {@link WriteService} takes the snapshot records,
 * so a snapshot class calling it back would make the two packages a cycle.
 */
@Component
class SnapshotImporter implements InitializingBean {

    private static final String SNAPSHOT_DIRECTORY = "snapshot";

    private final EmbeddedNeo4jStore store;
    private final WriteService writeService;
    private final String repo;

    SnapshotImporter(
            EmbeddedNeo4jStore store,
            WriteService writeService,
            @Value("${agentkb.repo:}") String repo) {
        this.store = store;
        this.writeService = writeService;
        this.repo = repo;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        // On every start: a fresh install has no snapshot, and a changed definition must reach an
        // existing graph.
        writeService.maintenance().ensureSchema();
        if (repo.isBlank() || !graphIsEmpty()) {
            return;
        }
        Path snapshot = Path.of(repo).resolve(SNAPSHOT_DIRECTORY);
        // No snapshot/ at all is a first run (a repo without a sync yet); one with a file missing
        // or broken is an error, which SnapshotFormat.read names.
        if (!Files.isDirectory(snapshot)) {
            return;
        }
        writeService.maintenance().importRecords(SnapshotFormat.read(snapshot));
    }

    private boolean graphIsEmpty() {
        return store.read(
                tx -> {
                    try (Result result = tx.execute("MATCH (n) RETURN n LIMIT 1")) {
                        return !result.hasNext();
                    }
                });
    }
}
