package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H7 review: what {@code kb doc add} takes as its file argument. Only a regular file is read
 * (CWE-835: a FIFO or device would block or never end); the argument is echoed without control
 * characters (CWE-150); a path the platform cannot express is exit 1, not a stack trace.
 */
class KbDocAddPathTest {

    @TempDir Path workDir;

    /** A directory named with an escape sequence and a carriage return, echoed without them. */
    @Test
    void a_directory_is_refused_and_its_name_is_echoed_without_control_characters()
            throws IOException {
        Path dir = Files.createDirectory(workDir.resolve("d\u001b[31mred\rforged.md"));

        try (UploadStub server = new UploadStub()) {
            Result result = server.kb(workDir, "doc", "add", dir.toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr())
                    .contains("not a regular file", "d[31mredforged.md")
                    .doesNotContain("\u001b", "\r");
            assertThat(server.requests("PUT")).isEmpty();
        }
    }

    /** Reading a FIFO blocks until a writer comes: kb refuses it before it opens it. */
    @Test
    void a_fifo_is_refused_without_blocking() throws IOException, InterruptedException {
        Path fifo = workDir.resolve("pipe");
        Assumptions.assumeTrue(mkfifo(fifo), "mkfifo is not available");

        try (UploadStub server = new UploadStub()) {
            Result result = server.kb(workDir, "doc", "add", fifo.toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("not a regular file");
            assertThat(server.requests("PUT")).isEmpty();
        }
    }

    /**
     * In-process: a process argument cannot hold NUL (ProcessBuilder refuses it), but a path the
     * platform rejects (NUL here, {@code <>:"|?*} on Windows) must still be exit 1.
     */
    @Test
    void a_path_the_platform_rejects_is_exit_one_with_the_argument_cleaned() {
        DocCommands.Doc.Add add = new DocCommands.Doc.Add();
        add.file = "a\u0000b\u001bc";

        KbException refused = catchThrowableOfType(KbException.class, add::call);

        assertThat(refused).isNotNull();
        assertThat(refused.exitCode()).isEqualTo(Kb.FAILED);
        assertThat(refused.getMessage()).isEqualTo("not a valid path: abc");
    }

    /** Whether {@code mkfifo path} made a FIFO; false where there is no mkfifo (Windows). */
    private static boolean mkfifo(Path path) throws InterruptedException {
        try {
            return new ProcessBuilder("mkfifo", path.toString()).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
