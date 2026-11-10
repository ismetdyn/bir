package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb check} in F3 (docs/CLIENTS.md §2, plan.md H7): every Document's blob is hashed. A blob
 * that is not there is {@code missing-blob}, one whose bytes do not hash to its sha256 (or a link
 * in its place) is {@code blob-hash-mismatch}. A problem names the document id and kind, never a
 * byte of the content (invariants 2 and 10).
 */
class CheckBlobTest {

    private static final String SEED_DOC = "doc:ded48257b6f3";
    private static final String SEED_BLOB =
            "blobs/de/ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf4";
    private static final String PLANTED = "kbtest-planted-line-7c41 password=kbtest-FAKE-blob";

    @TempDir Path dir;

    private EmbeddedNeo4jStore store;
    private Path repo;
    private CheckService check;

    @BeforeEach
    void seedRepoWithItsBlob() throws IOException {
        repo =
                SnapshotRepos.repo(
                        dir,
                        SnapshotRepos.without(
                                SnapshotRepos.seed(), Set.of(SnapshotRepos.SEED_CONFLICT_FACT)));
        SnapshotRepos.copySeedBlobs(repo);
        store = new EmbeddedNeo4jStore(dir.resolve("data"));
        WriteService writes = new WriteService(store, new RepoSchema(repo.toString()));
        writes.maintenance().ensureSchema();
        writes.maintenance().importRecords(SnapshotFormat.read(repo.resolve("snapshot")));
        check = new CheckService(store, repo.toString());
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void an_intact_blob_is_no_problem() {
        assertThat(check.check().problems()).isEmpty();
    }

    @Test
    void a_missing_blob_is_reported_by_document_id() throws IOException {
        Files.delete(repo.resolve(SEED_BLOB));

        assertThat(check.check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("missing-blob", SEED_DOC));
    }

    @Test
    void a_changed_blob_is_a_hash_mismatch_and_its_content_is_not_reported() throws IOException {
        Files.writeString(repo.resolve(SEED_BLOB), PLANTED + "\n", StandardCharsets.UTF_8);

        CheckReport report = check.check();

        assertThat(report.problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("blob-hash-mismatch", SEED_DOC));
        assertThat(report.toString()).doesNotContain("kbtest-planted", "kbtest-FAKE");
    }

    /**
     * CP4 S2: a Document that came with a snapshot may carry any sha256; one that is no sha256
     * finds no blob and leads nowhere outside blobs/.
     */
    @Test
    void a_document_whose_sha256_is_no_sha256_has_a_missing_blob() {
        store.database()
                .executeTransactionally(
                        "MATCH (d:Document {id: $id}) SET d.sha256 = '../../snapshot/nodes.jsonl'",
                        Map.of("id", SEED_DOC));

        assertThat(check.check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("missing-blob", SEED_DOC));
    }

    /**
     * CWE-59: a link in the blob's place is not followed, so it does not count as the blob even
     * when its target holds the right bytes.
     */
    @Test
    void a_link_in_place_of_the_blob_is_a_hash_mismatch() throws IOException {
        Path blob = repo.resolve(SEED_BLOB);
        Path copy = Files.copy(blob, dir.resolve("intact-copy"));
        Files.delete(blob);
        Files.createSymbolicLink(blob, copy);

        assertThat(check.check().problems())
                .extracting(CheckReport.Problem::kind, CheckReport.Problem::id)
                .containsExactly(tuple("blob-hash-mismatch", SEED_DOC));
    }
}
