package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import io.github.ismetdyn.agentkb.client.UploadStub.Request;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * plan.md H7, client side of {@code kb doc add} (ADR-0025 decision 1, docs/CLIENTS.md §2): kb
 * hashes the file as a stream, PUTs to learn the server's offset, PATCHes at most 1 MiB at a time
 * from that offset and completes with POST /documents. It keeps no state: a second run after a
 * broken connection resumes from what the server holds. KbDocAddRefusalTest has the refusals.
 */
class KbDocAddTest {

    private static final int MIB = 1024 * 1024;

    @TempDir Path workDir;

    @Test
    void the_file_goes_up_in_parts_of_at_most_one_mib_then_is_completed() throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 2 * MIB + MIB / 2);
        String sha = UploadStub.sha256(Files.readAllBytes(file));

        try (UploadStub server = new UploadStub()) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            Request put = server.requests("PUT").getFirst();
            assertThat(put.path()).isEqualTo(UploadStub.UPLOADS + sha);
            assertThat(put.uploadLength()).isEqualTo(Integer.toString(2 * MIB + MIB / 2));
            List<Request> patches = server.requests("PATCH");
            assertThat(patches)
                    .extracting(Request::uploadOffset)
                    .containsExactly("0", Integer.toString(MIB), Integer.toString(2 * MIB));
            assertThat(patches)
                    .allSatisfy(
                            patch -> {
                                assertThat(patch.body().length).isLessThanOrEqualTo(MIB);
                                assertThat(patch.contentType())
                                        .isEqualTo("application/offset+octet-stream");
                            });
            assertThat(server.requests("POST").getFirst().text())
                    .containsPattern(ApiStub.jsonField("sha256", sha))
                    .containsPattern(ApiStub.jsonField("name", "notes.md"))
                    .doesNotContain("about", "origin", "replaces");
            assertThat(result.stdout())
                    .contains("added", "doc:" + sha.substring(0, 12), UploadStub.BLOB_DIR + sha);
            assertThat(result.stderr()).as("no warning for a plain file").isEmpty();
        }
    }

    /**
     * ADR-0025 decision 3: the server cannot scan inside a compressed file or container, so kb
     * warns after the add; the add itself succeeds. Magics: zip (docx, xlsx), gzip, PDF, 7z, xz,
     * zstd, bzip2.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "504b0304",
                "1f8b08",
                "255044462d",
                "377abcaf271c",
                "fd377a585a00",
                "28b52ffd",
                "425a6839"
            })
    void a_compressed_file_is_added_with_a_warning_on_stderr(String magic) throws IOException {
        byte[] head = HexFormat.of().parseHex(magic);
        byte[] bytes = Arrays.copyOf(head, head.length + 100);
        // H7 review (CWE-150): an OSC retitle sequence and a carriage return in the name.
        Path file = Files.write(workDir.resolve("pa\u001b]0;t\u0007\rcked"), bytes);

        try (UploadStub server = new UploadStub()) {
            Result result = server.kb(workDir, "doc", "add", file.toString());

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("added");
            assertThat(result.stderr())
                    .contains("warning", "pa]0;tcked", "not scanned for secrets")
                    .doesNotContain("\n\n", "\u001b", "\u0007", "\r");
        }
    }

    @Test
    void name_about_origin_and_replaces_go_into_the_completion() throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", 10);

        try (UploadStub server = new UploadStub()) {
            Result result =
                    server.kb(
                            workDir,
                            "doc",
                            "add",
                            file.toString(),
                            "--name",
                            // ASCII: under LC_ALL=C the JVM cannot decode other argument bytes.
                            "run \"v2\".md",
                            "--about",
                            "ornek-app-01",
                            "--origin",
                            "wiki.ornek.example/x",
                            "--replaces",
                            "doc:ded48257b6f3");

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(server.requests("POST").getFirst().text())
                    .containsPattern(ApiStub.jsonField("name", "run \\\"v2\\\".md"))
                    .containsPattern(ApiStub.jsonField("about", "ornek-app-01"))
                    .containsPattern(ApiStub.jsonField("origin", "wiki.ornek.example/x"))
                    .containsPattern(ApiStub.jsonField("replaces", "doc:ded48257b6f3"));
        }
    }

    @Test
    void adding_the_same_file_again_sends_no_part_and_reports_the_known_document()
            throws IOException {
        Path file = UploadStub.file(workDir, "notes.md", MIB + 1);

        try (UploadStub server = new UploadStub()) {
            assertThat(server.kb(workDir, "doc", "add", file.toString()).exitCode()).isZero();
            int patchesBefore = server.requests("PATCH").size();

            Result again = server.kb(workDir, "doc", "add", file.toString());

            assertThat(again.exitCode()).as(again.stderr()).isZero();
            assertThat(server.requests("PATCH")).hasSize(patchesBefore);
            assertThat(again.stdout()).contains("already stored");
        }
    }

    @Test
    void a_rerun_after_a_broken_connection_resumes_from_the_servers_offset() throws IOException {
        Path file = UploadStub.file(workDir, "big.bin", 3 * MIB + 7);

        try (UploadStub server = new UploadStub(2, n -> {}, Long.MAX_VALUE)) {
            Result broken = server.kb(workDir, "doc", "add", file.toString());

            assertThat(broken.exitCode()).as(broken.stderr()).isEqualTo(3);
            assertThat(broken.stderr())
                    .contains("connection lost during upload; run the same command again to resume")
                    .doesNotContain("is it running");
            assertThat(server.requests("POST")).isEmpty();
            int patchesBefore = server.requests("PATCH").size();

            Result resumed = server.kb(workDir, "doc", "add", file.toString());

            assertThat(resumed.exitCode()).as(resumed.stderr()).isZero();
            List<Request> patches = server.requests("PATCH");
            // The stub kept half of part 2: the server's offset, not a part boundary kb could
            // guess.
            assertThat(patches.subList(patchesBefore, patches.size()))
                    .extracting(Request::uploadOffset)
                    .containsExactly(
                            Integer.toString(MIB + MIB / 2), Integer.toString(2 * MIB + MIB / 2));
            assertThat(resumed.stdout()).contains("added");
        }
    }
}
