package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * plan.md H7: when {@code kb doc add} stops. A file that changed under the upload is never stored
 * as something it is not (exit 1: the server's 422, or kb's own message if the file shrank); a file
 * over 20 MB without git-lfs is refused at PUT, before any PATCH; a missing file sends nothing; an
 * unreachable server is exit 3.
 */
class KbDocAddRefusalTest {

    private static final int MIB = 1024 * 1024;
    private static final int UNREACHABLE = 3;

    @TempDir Path workDir;

    @Test
    void a_file_changed_during_the_upload_is_refused_with_the_servers_422() throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 3 * MIB);

        try (UploadStub server = new UploadStub(0, n -> change(file, n, false), Long.MAX_VALUE)) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("422", "do not have this sha256");
            assertThat(result.stdout()).isEmpty();
        }
    }

    @Test
    void a_file_that_shrank_during_the_upload_is_refused_before_the_completion()
            throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 3 * MIB);

        try (UploadStub server = new UploadStub(0, n -> change(file, n, true), Long.MAX_VALUE)) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("changed").doesNotContain("Exception");
            assertThat(server.requests("POST")).isEmpty();
        }
    }

    @Test
    void a_large_file_without_git_lfs_is_refused_at_put_before_any_part() throws IOException {
        long twentyMb = 20L * 1000 * 1000;
        Path file = workDir.resolve("big.bin");
        try (RandomAccessFile big = new RandomAccessFile(file.toFile(), "rw")) {
            big.setLength(twentyMb + 1);
        }

        try (UploadStub server = new UploadStub(0, n -> {}, twentyMb)) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("422", "git-lfs");
            assertThat(server.requests("PUT")).hasSize(1);
            assertThat(server.requests("PATCH")).isEmpty();
            assertThat(server.requests("POST")).isEmpty();
        }
    }

    @Test
    void a_missing_file_exits_one_without_a_request() throws IOException {
        try (UploadStub server = new UploadStub()) {
            Result result = server.kb(workDir, "doc", "add", workDir.resolve("nope.md").toString());

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("nope.md").doesNotContain("Exception");
            assertThat(server.requests("PUT")).isEmpty();
        }
    }

    @Test
    void an_unreachable_server_exits_three() throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 10);
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }

        Result result = KbProcess.run(closed, "", workDir, "doc", "add", file.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(UNREACHABLE);
        assertThat(result.stderr()).contains("127.0.0.1");
    }

    /**
     * A PUT answer whose Upload-Offset is no byte count of the file (below 0, past its 10 bytes,
     * not a number) is exit 1, and kb sends nothing more.
     */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "11", "x"})
    void an_upload_offset_outside_the_file_is_refused(String offset) throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 10);
        String upload = UploadStub.UPLOADS + UploadStub.sha256(Files.readAllBytes(file));

        try (ApiStub server =
                new ApiStub().answer("PUT", upload, 201, "", Map.of("Upload-Offset", offset))) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
            assertThat(result.stderr()).contains("Upload-Offset");
            assertThat(server.requests())
                    .extracting(ApiStub.Request::method)
                    .containsExactly("PUT");
        }
    }

    /** A PATCH answered with the offset it was sent at took nothing: exit 1, no loop, no POST. */
    @Test
    void a_patch_that_does_not_advance_the_offset_is_refused() throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 10);
        String upload = UploadStub.UPLOADS + UploadStub.sha256(Files.readAllBytes(file));

        try (ApiStub server =
                new ApiStub()
                        .answer("PUT", upload, 201, "", Map.of("Upload-Offset", "0"))
                        .answer("PATCH", upload, 204, "", Map.of("Upload-Offset", "0"))) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
            assertThat(server.requests())
                    .extracting(ApiStub.Request::method)
                    .containsExactly("PUT", "PATCH");
        }
    }

    /**
     * When PATCH 2 arrives kb has read parts 1 and 2 but not 3: the file shrinks to 1.5 MiB, or its
     * last byte changes and the size stays.
     */
    private static void change(Path file, int patch, boolean shrink) {
        if (patch != 2) {
            return;
        }
        try (RandomAccessFile changed = new RandomAccessFile(file.toFile(), "rw")) {
            if (shrink) {
                changed.setLength(MIB + MIB / 2);
                return;
            }
            changed.seek(changed.length() - 1);
            int last = changed.read();
            changed.seek(changed.length() - 1);
            changed.write(last ^ 0xff);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
