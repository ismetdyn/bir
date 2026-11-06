package io.github.ismetdyn.agentkb.server.hooks;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.HOOK_BUDGET;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.SESSION_START;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.context;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ObjectNode;

/**
 * SPEC S1 and ARCHITECTURE §11: a SessionStart in a git working copy under {@code
 * agentkb.scan.allowed-roots} whose HEAD differs from the commit its last scan read starts a scan
 * in the background; the hook answers within its budget without waiting for it. An unchanged
 * repository or a folder outside the roots is not scanned, and two quick SessionStarts start at
 * most one scan.
 *
 * <p>Contract fixed by this test: the scan records the HEAD it read as {@code commit} on its Repo
 * node. A scan counts by its {@code event=scan_done} log line (ScannerService), the graph shows its
 * result. A scan is held open by {@link GitFifo} on {@code .git/info/exclude}: reading HEAD does
 * not open it, the scan's {@code git ls-files --exclude-standard} blocks there.
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionStartScanTest extends RunningServerTestBase {

    private static final String APPLICATION_YML = "src/main/resources/application.yml";
    private static final String SCAN_DONE = "event=scan_done";
    private static final String BRIEF_TITLE = "# agentkb brief";
    // A tiny repository scans in well under a second; this only bounds a hung background scan.
    private static final Duration SCAN_WAIT = Duration.ofSeconds(30);
    // How long a scan that must not happen is given to show up.
    private static final Duration QUIET = Duration.ofSeconds(3);
    private static final String REPO_COMMIT =
            "MATCH (r:Repo {id: $id}) WHERE r.commit = $commit RETURN count(r) AS n";
    private static final String ENTITY = "MATCH (e:Entity {id: $id}) RETURN count(e) AS n";
    private static final String ENTITY_BATCH = "MATCH (e:Entity {id: $id}) RETURN e.batch AS batch";

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path outside;

    @Test
    void a_session_in_a_new_repository_under_the_roots_scans_it_and_records_its_head()
            throws Exception {
        Path repo = project(scanRoot(), "new");

        String brief = context(SESSION_START, sessionStart(repo));

        assertThat(brief).contains(BRIEF_TITLE);
        Poll.until(
                SCAN_WAIT,
                () -> isEntity(repo.getFileName() + "-db") && isScannedAtHead(repo),
                "the background scan wrote the database and recorded HEAD");
    }

    @Test
    void the_hook_answers_within_its_budget_while_the_scan_still_runs() throws Exception {
        Path repo = project(scanRoot(), "held");
        Path fifo = GitFifo.make(repo.resolve(".git/info/exclude"));
        try {
            long started = System.nanoTime();
            HttpResponse<String> reply = sessionStart(repo);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took)
                    .as("SessionStart while the scan cannot finish")
                    .isLessThan(HOOK_BUDGET);
            assertThat(context(SESSION_START, reply)).contains(BRIEF_TITLE);
            assertThat(isEntity(repo.getFileName() + "-db")).as("the scan is still held").isFalse();
        } finally {
            GitFifo.release(fifo);
        }
        Poll.until(
                SCAN_WAIT,
                () -> isEntity(repo.getFileName() + "-db") && isScannedAtHead(repo),
                "the scan, released, finished after the hook had answered");
    }

    @Test
    void an_unchanged_head_starts_no_scan_and_a_new_commit_starts_one(CapturedOutput output)
            throws Exception {
        Path repo = project(scanRoot(), "unchanged");
        context(SESSION_START, sessionStart(repo));
        Poll.until(
                SCAN_WAIT,
                () -> isEntity(repo.getFileName() + "-db") && isScannedAtHead(repo),
                "the first scan");
        long scans = scans(output);
        // A change outside a commit: a scan would find it, the HEAD check must not start one.
        Path yml = repo.resolve(APPLICATION_YML);
        Files.writeString(yml, Files.readString(yml).replace("-db.", "-db2."));

        context(SESSION_START, sessionStart(repo));

        Poll.stays(
                QUIET,
                () -> scans(output) == scans && !isEntity(repo.getFileName() + "-db2"),
                "no scan for an unchanged HEAD");
        ScanProjects.commitAll(repo, "move the database");

        context(SESSION_START, sessionStart(repo));

        Poll.until(
                SCAN_WAIT,
                () -> isEntity(repo.getFileName() + "-db2") && isScannedAtHead(repo),
                "the scan of the new HEAD");
    }

    @Test
    void a_working_copy_outside_the_allowed_roots_is_not_scanned(CapturedOutput output)
            throws Exception {
        Path repo = project(outside, "outside");
        long scans = scans(output);

        String brief = context(SESSION_START, sessionStart(repo));

        assertThat(brief).contains(BRIEF_TITLE);
        Poll.stays(
                QUIET,
                () -> scans(output) == scans && !isEntity(repo.getFileName().toString()),
                "no scan outside agentkb.scan.allowed-roots");
    }

    /**
     * Both SessionStarts come while the first scan is held, so a second scan could not be told
     * apart by a recorded HEAD; at most one may run, and one does.
     */
    @Test
    void two_quick_session_starts_start_at_most_one_scan(CapturedOutput output) throws Exception {
        Path repo = project(scanRoot(), "twice");
        long scans = scans(output);
        Path fifo = GitFifo.make(repo.resolve(".git/info/exclude"));
        try {
            context(SESSION_START, sessionStart(repo));
            context(SESSION_START, sessionStart(repo));
            // ponytail: a fixed pause so every scan started reaches the held git before release.
            TimeUnit.SECONDS.sleep(1);
        } finally {
            GitFifo.release(fifo);
        }
        Poll.until(SCAN_WAIT, () -> isEntity(repo.getFileName() + "-db"), "the one scan finished");

        Poll.stays(
                QUIET, () -> scans(output) <= scans + 1, "at most one scan for two SessionStarts");
        assertThat(scans(output)).isEqualTo(scans + 1);
    }

    /**
     * ADR-0023 decision 3: a scan with a session uses that session's Episode. The scan SessionStart
     * starts has the session, so every record its batch opens carries the Episode SessionStart
     * named, not one of {@code kind=scan}.
     */
    @Test
    void the_scan_a_session_start_starts_stamps_the_sessions_episode() throws Exception {
        Path repo = project(scanRoot(), "episode");
        EpisodeProbe probe = new EpisodeProbe(port, store);
        Session session = probe.start("claude", newSessionId(), repo);
        String db = repo.getFileName() + "-db";
        Poll.until(
                SCAN_WAIT,
                () -> isEntity(db) && isScannedAtHead(repo),
                "the background scan wrote the database");

        String batch =
                (String)
                        store.read(
                                tx ->
                                        tx.execute(ENTITY_BATCH, Map.of("id", db))
                                                .next()
                                                .get("batch"));

        assertThat(probe.stamps(batch))
                .as("episode of every record the scan's batch %s opened", batch)
                .isNotEmpty()
                .containsOnly(session.episode());
    }

    /** A new session (its own session_id) of Claude Code in {@code cwd}. */
    private HttpResponse<String> sessionStart(Path cwd) throws IOException, InterruptedException {
        ObjectNode payload =
                HookHttp.sessionStart("claude", cwd)
                        .put("session_id", UUID.randomUUID().toString());
        return new HookHttp(port).post(SESSION_START, "claude", payload);
    }

    /**
     * A committed Spring project {@code zz-s1-<what>-<random>} (remote zz-grup/<that name>) in
     * {@code dir}, whose datasource host {@code <that name>-db} is an entity of its own.
     */
    private static Path project(Path dir, String what) {
        String name = "zz-s1-" + what + "-" + UUID.randomUUID().toString().substring(0, 8);
        String yml =
                """
                spring:
                  application:
                    name: %s
                  datasource:
                    url: jdbc:postgresql://%s-db.ornek.example:5432/zzdb
                """
                        .formatted(name, name);
        return ScanProjects.project(dir, name, Map.of(APPLICATION_YML, yml));
    }

    /** Whether the Repo of {@code repo} records the working copy's current HEAD as scanned. */
    private boolean isScannedAtHead(Path repo) {
        String head = ScanProjects.git(repo, "rev-parse", "HEAD").strip();
        Map<String, Object> params = Map.of("id", "zz-grup/" + repo.getFileName(), "commit", head);
        return store.read(tx -> (Long) tx.execute(REPO_COMMIT, params).next().get("n")) > 0;
    }

    private boolean isEntity(String id) {
        return store.read(tx -> (Long) tx.execute(ENTITY, Map.of("id", id)).next().get("n")) > 0;
    }

    private static long scans(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(SCAN_DONE)).count();
    }
}
