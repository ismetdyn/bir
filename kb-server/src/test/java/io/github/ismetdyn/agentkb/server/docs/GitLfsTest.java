package io.github.ismetdyn.agentkb.server.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sync remote can commit {@code .gitattributes} as a link, and {@code git lfs track} appends
 * through it (CWE-59). The refusal comes before git runs, so this holds without git-lfs too.
 */
class GitLfsTest {

    private static final String VICTIM = "the user's own file\n";

    @TempDir Path repo;
    @TempDir Path elsewhere;

    @Test
    void a_linked_gitattributes_is_refused_and_its_target_is_untouched() throws IOException {
        Path victim = Files.writeString(elsewhere.resolve("victim"), VICTIM);
        Files.createSymbolicLink(repo.resolve(".gitattributes"), victim);

        assertThatThrownBy(() -> GitLfs.track(repo, "blobs/ab/x"))
                .isInstanceOf(WriteRejectedException.class)
                .hasMessageNotContaining(elsewhere.toString());

        assertThat(Files.readString(victim)).isEqualTo(VICTIM);
    }
}
