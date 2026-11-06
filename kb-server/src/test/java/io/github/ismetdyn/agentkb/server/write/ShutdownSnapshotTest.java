package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.snapshot.SnapshotRepos.nodeIds;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * ADR-0022 decision 44: the server writes the snapshot when it shuts down and commits it locally if
 * it changed; the push waits for the next {@code kb sync}. The server is started the way a user
 * starts it, on a clone of a local bare remote (invariant 3).
 */
class ShutdownSnapshotTest {

    private static final String HOST = "zz-shutdown-host";
    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");

    @TempDir Path dir;

    @Test
    void a_graceful_shutdown_commits_the_snapshot_locally_without_pushing() throws IOException {
        try (SyncMachines machines = SyncMachines.withSeedRemote(dir)) {
            Path kb = machines.plainClone("kb");

            try (ConfigurableApplicationContext context = start(kb)) {
                context.getBean(WriteService.class)
                        .write(request(Source.USER, HOST, T1, fact("os", "rhel 9", PROD)));
            }

            assertThat(nodeIds(SnapshotFormat.read(kb.resolve("snapshot")))).contains(HOST);
            assertThat(git(kb, "rev-parse", "HEAD"))
                    .as("a local commit on top of the seed")
                    .isNotEqualTo(machines.seedCommit);
            assertThat(git(kb, "show", "HEAD:snapshot/" + SnapshotFormat.NODES_FILE))
                    .contains("\"" + HOST + "\"");
            assertThat(git(kb, "status", "--porcelain", "snapshot")).as("nothing left").isEmpty();
            assertThat(machines.remoteHead()).as("no push").isEqualTo(machines.seedCommit);
        }
    }

    private ConfigurableApplicationContext start(Path kb) {
        return new SpringApplication(AgentkbServerApplication.class)
                .run(
                        "--server.port=0",
                        "--agentkb.data-dir=" + dir.resolve("data"),
                        "--agentkb.repo=" + kb);
    }

    private static String git(Path repo, String... args) {
        return ScanProjects.git(repo, args).strip();
    }
}
