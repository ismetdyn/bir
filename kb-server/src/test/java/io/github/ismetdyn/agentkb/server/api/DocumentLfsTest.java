package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
