package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb check} in F2 (ADR-0022 decisions 6 and 38, ARCHITECTURE §13): orphan edges, relation
 * types outside schema/relations.yaml, open conflicts, an id on two records, local records a
 * restore left (a Secret no entity holds, a conflict signal about nothing) and {@code git fsck} of
 * {@code ~/kb}. Contract: {@code new CheckService(store, repo).check()} gives a {@code CheckReport}
 * whose {@code problems()} are {@code Problem(kind, id, detail)} and whose {@code clean()} is true
 * only without problems. Kinds: orphan-edge, unknown-relation, open-conflict, duplicate-id,
 * local-record, git-fsck. The graph is read; the snapshot files are read from the working tree of
 * {@code <repo>/snapshot}, where an orphan edge can exist (the graph cannot hold one).
 */
class CheckServiceTest {

    private static final String SECRET_VALUE = "kbtest-FAKE-check-5d1e9a07";

    @TempDir Path dir;

    private EmbeddedNeo4jStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    /** seed/README.md keeps one open conflict on purpose; it is the seed's only finding. */
    @Test
    void the_seed_reports_its_one_open_conflict_and_nothing_else() throws IOException {
        CheckReport report = open(SnapshotRepos.repo(dir, SnapshotRepos.seed())).check();

        assertThat(report.problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("open-conflict", SnapshotRepos.SEED_CONFLICT_FACT));
        assertThat(report.clean()).isFalse();
    }

    @Test
    void a_healthy_repo_reports_clean() throws IOException {
        CheckReport report = open(SnapshotRepos.repo(dir, withoutSeedConflict())).check();

        assertThat(report.problems()).isEmpty();
        assertThat(report.clean()).isTrue();
    }

    @Test
    void an_edge_row_pointing_to_a_missing_node_is_an_orphan_edge() throws IOException {
        Path repo = SnapshotRepos.repo(dir, withoutSeedConflict());
        CheckService check = open(repo);
        SnapshotRepos.appendLine(
                repo,
                SnapshotFormat.EDGES_FILE,
                "{\"from\": \"ornek-app-01\", \"id\": \"01KBTESTORPHANEDGE00000001\", \"props\":"
                        + " {}, \"to\": \"kbtest-ghost\", \"type\": \"DEPENDS_ON\"}");

        CheckReport report = check.check();

        assertThat(report.problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("orphan-edge", "01KBTESTORPHANEDGE00000001"));
        assertThat(report.problems().getFirst().detail()).contains("kbtest-ghost");
    }

    /** SPEC §6.2: the fixed edges (HAS_FACT, ABOUT …) are no vocabulary types and pass. */
    @Test
    void a_relation_type_outside_the_vocabulary_is_reported() throws IOException {
        CheckService check = open(SnapshotRepos.repo(dir, withoutSeedConflict()));
        store.database()
                .executeTransactionally(
                        "MATCH (a:Entity {id: 'ornek-app-01'}), (b:Entity {id: 'ornek-lb'})"
                                + " CREATE (a)-[:KBTEST_UNKNOWN {id: '01KBTESTUNKNOWNREL00000001'}]->(b)");

        assertThat(check.check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("unknown-relation", "01KBTESTUNKNOWNREL00000001"));
    }

    /** Each label has its own id constraint, so only a check sees one id under two labels. */
    @Test
    void one_id_on_records_of_two_labels_is_reported() throws IOException {
        CheckService check = open(SnapshotRepos.repo(dir, withoutSeedConflict()));
        store.database()
                .executeTransactionally(
                        "CREATE (:Decision {id: 'ornek-app-01', rule: 'kbtest', status:"
                                + " 'accepted'})");

        assertThat(check.check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("duplicate-id", "ornek-app-01"));
    }

    /**
     * ADR-0022 decision 38: a restore keeps a Secret whose entity the snapshot lacks and a signal
     * whose ABOUT target it lacks; check lists both, never the secret value (invariant 2). A
     * qa-capture signal has no ABOUT by design and is no finding.
     */
    @Test
    void records_a_restore_left_behind_are_listed_without_their_value() throws IOException {
        CheckService check = open(SnapshotRepos.repo(dir, withoutSeedConflict()));
        store.database()
                .executeTransactionally(
                        """
                        CREATE (:Secret {id: 'kbtest-gone/prod/token', name: 'token', env: 'prod',
                                         source: 'user', secret_value: $value})
                        CREATE (:Signal {id: '01KBTESTSIGNALABOUTNONE001', kind: 'conflict',
                                         key: 'kbtest-gone/ip/prod', payload: 'ip disputed',
                                         status: 'open', count: 1})
                        CREATE (:Signal {id: '01KBTESTSIGNALQACAPTURE001', kind: 'qa-capture',
                                         key: 'kbtest question', payload: 'answer',
                                         status: 'open', count: 1})
                        """,
                        Map.of("value", SECRET_VALUE));

        CheckReport report = check.check();

        assertThat(report.problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactlyInAnyOrder(
                        tuple("local-record", "kbtest-gone/prod/token"),
                        tuple("local-record", "01KBTESTSIGNALABOUTNONE001"));
        assertThat(report.toString()).doesNotContain(SECRET_VALUE);
    }

    @Test
    void a_corrupted_repo_reports_git_fsck_errors() throws IOException {
        Path repo = SnapshotRepos.repo(dir, withoutSeedConflict());
        CheckService check = open(repo);
        SnapshotRepos.corruptObjects(repo);

        CheckReport report = check.check();

        assertThat(report.problems())
                .extracting(CheckReport.Problem::kind)
                .containsOnly("git-fsck");
        assertThat(report.clean()).isFalse();
    }

    /** The graph loaded from the repo's snapshot, as on a start with this repo, and its check. */
    private CheckService open(Path repo) throws IOException {
        SnapshotRepos.copySeedBlobs(repo);
        store = new EmbeddedNeo4jStore(dir.resolve("data"));
        WriteService writes = new WriteService(store, new RepoSchema(repo.toString()));
        writes.maintenance().ensureSchema();
        writes.maintenance().importRecords(SnapshotFormat.read(repo.resolve("snapshot")));
        return new CheckService(store, repo.toString());
    }

    private static Snapshot withoutSeedConflict() {
        return SnapshotRepos.without(
                SnapshotRepos.seed(), Set.of(SnapshotRepos.SEED_CONFLICT_FACT));
    }
}
