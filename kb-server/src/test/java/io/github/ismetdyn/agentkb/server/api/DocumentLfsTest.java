package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A document over 20 MB goes to git-lfs (ADR-0017 decision 2, ADR-0025 decision 5): {@code git lfs
 * track} for its blob, and {@code git lfs install --local} in the knowledge repo only, never in the
 * user's global git config.
 *
 * <p>Needs git-lfs; where it is missing every test here is reported as not run with that reason.
 * The server runs git in this JVM's environment, so the global config must already point at a
 * throwaway file (GIT_CONFIG_GLOBAL); otherwise the test fails rather than risk the user's real
 * ~/.gitconfig.
 */
class DocumentLfsTest extends DocumentControllerTestBase {

    // Over 20 MB read as either 20,000,000 or 20 MiB (UploadControllerPutTest).
    private static final int OVER_LFS_THRESHOLD = 25 * MIB;
    private static final String NAME = "zz-h4-large.bin";
    private static final String VICTIM = "the user's own file\n";

    @Test
    void a_document_over_20_mb_is_tracked_by_git_lfs_in_the_repo_only() throws Exception {
        Assumptions.assumeTrue(UploadControllerHttp.gitLfsPresent(), "not run: git-lfs missing");
        String isolated = System.getenv("GIT_CONFIG_GLOBAL");
        assertThat(isolated)
                .as("GIT_CONFIG_GLOBAL must point at a throwaway file for this test JVM")
                .isNotBlank()
                .doesNotStartWith(System.getProperty("user.home") + "/.");
        Path repo = committedRepo();
        byte[] content = new byte[OVER_LFS_THRESHOLD];
        byte[] text = uniqueText();
        System.arraycopy(text, 0, content, 0, text.length);
        String sha = documents.upload(content);

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(Files.readString(repo.resolve(".gitattributes"), StandardCharsets.UTF_8))
                .contains(DocumentHttp.blobPath(sha))
                .contains("filter=lfs");
        assertThat(Files.readString(repo.resolve(".git").resolve("config")))
                .as("git lfs install --local")
                .contains("[filter \"lfs\"]");
        Path global = Path.of(isolated);
        assertThat(Files.exists(global) ? Files.readString(global) : "")
                .as("nothing written to the global git config")
                .doesNotContain("lfs");
    }

    /**
     * Tracked before the move (CP2 J1): a blob over the threshold that git-lfs could not track
     * would be committed raw by the next kb sync, and history is never rewritten. Here git lfs
     * fails because the repository's {@code .git} is moved aside for the call.
     */
    @Test
    void a_blob_git_lfs_cannot_track_never_reaches_blobs() throws Exception {
        Assumptions.assumeTrue(UploadControllerHttp.gitLfsPresent(), "not run: git-lfs missing");
        Path repo = committedRepo();
        byte[] content = new byte[OVER_LFS_THRESHOLD];
        byte[] text = uniqueText();
        System.arraycopy(text, 0, content, 0, text.length);
        String sha = documents.upload(content);
        Path git = repo.resolve(".git");
        Path aside = Files.move(git, repo.resolve(".git-aside"));
        try {
            HttpResponse<String> reply = documents.complete(sha, NAME);

            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(500);
            assertThat(blobFiles(sha)).as("nothing under blobs/ for git to commit raw").isEmpty();
            assertThat(stagedFiles(sha)).as("the upload is kept for a retry").hasSize(1);
            assertThat(documentNodes(sha)).isEmpty();
        } finally {
            Files.move(aside, git);
        }
    }

    /**
     * A sync remote can commit {@code .gitattributes} as a link, and {@code git lfs track} appends
     * through it (CWE-59): the add is refused before git lfs runs, the upload stays staged.
     */
    @Test
    void a_linked_gitattributes_is_refused_and_its_target_is_untouched(@TempDir Path elsewhere)
            throws Exception {
        Assumptions.assumeTrue(UploadControllerHttp.gitLfsPresent(), "not run: git-lfs missing");
        Path repo = committedRepo();
        byte[] content = new byte[OVER_LFS_THRESHOLD];
        byte[] text = uniqueText();
        System.arraycopy(text, 0, content, 0, text.length);
        String sha = documents.upload(content);
        Path victim = Files.writeString(elsewhere.resolve("victim"), VICTIM);
        Path attributes = repo.resolve(".gitattributes");
        Path aside = repo.resolve(".gitattributes-aside");
        boolean hadAttributes = Files.exists(attributes, LinkOption.NOFOLLOW_LINKS);
        if (hadAttributes) {
            Files.move(attributes, aside);
        }
        Files.createSymbolicLink(attributes, victim);
        try {
            HttpResponse<String> reply = documents.complete(sha, NAME);

            assertThat(Files.readString(victim))
                    .as("the link target is untouched")
                    .isEqualTo(VICTIM);
            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
            assertThat(reply.body()).contains("hint");
            assertNoLeak(reply.body());
            assertThat(blobFiles(sha)).as("nothing under blobs/").isEmpty();
            assertThat(stagedFiles(sha)).as("the upload is kept for a retry").hasSize(1);
            assertThat(documentNodes(sha)).isEmpty();
        } finally {
            Files.delete(attributes);
            if (hadAttributes) {
                Files.move(aside, attributes);
            }
        }
    }

    @Test
    void a_document_under_the_threshold_is_not_tracked_by_git_lfs() throws Exception {
        Assumptions.assumeTrue(UploadControllerHttp.gitLfsPresent(), "not run: git-lfs missing");
        Path repo = committedRepo();
        String sha = documents.upload(uniqueText());

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        Path attributes = repo.resolve(".gitattributes");
        assertThat(Files.exists(attributes) ? Files.readString(attributes) : "")
                .doesNotContain(sha);
    }
}
