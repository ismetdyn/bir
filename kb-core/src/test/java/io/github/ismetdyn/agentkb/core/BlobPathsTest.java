package io.github.ismetdyn.agentkb.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The blob layout and who may use it (SPEC §6.7): only 64 lowercase hex digits make a path, so a
 * sha256 that came with a snapshot cannot lead out of blobs/; a path is handed out only for a
 * regular file in real directories, never through a link (CWE-59).
 */
class BlobPathsTest {

    private static final String SHA =
            "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf4";

    @TempDir Path repo;

    @Test
    void a_sha256_makes_its_path_under_blobs() {
        assertThat(BlobPaths.of(SHA)).contains("blobs/de/" + SHA);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "..",
                "../../etc/passwd",
                "de/../../etc",
                "/etc/passwd",
                "DED48257B6F34156D85BFD03C6AA6C4D3724D713D417771E1CCF7C68D398DBF4",
                "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf",
                "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf44",
                "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf\n",
                "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbg4"
            })
    void anything_but_64_lowercase_hex_digits_makes_no_path(String sha) {
        assertThat(BlobPaths.of(sha)).isEmpty();
    }

    @Test
    void a_regular_blob_file_is_handed_out() throws IOException {
        Path blob = Files.createDirectories(repo.resolve("blobs/de")).resolve(SHA);
        Files.writeString(blob, "x");

        assertThat(BlobPaths.regularFile(repo, SHA)).contains(blob);
    }

    @Test
    void a_missing_blob_is_not_handed_out() {
        assertThat(BlobPaths.regularFile(repo, SHA)).isEmpty();
    }

    @Test
    void a_link_in_place_of_the_blob_is_not_handed_out() throws IOException {
        Path elsewhere = Files.writeString(repo.resolve("elsewhere"), "x");
        Path blob = Files.createDirectories(repo.resolve("blobs/de")).resolve(SHA);
        Files.createSymbolicLink(blob, elsewhere);

        assertThat(BlobPaths.regularFile(repo, SHA)).isEmpty();
    }

    @Test
    void a_linked_directory_on_the_way_is_not_followed() throws IOException {
        Path outside = Files.createDirectories(repo.resolve("outside"));
        Files.writeString(outside.resolve(SHA), "x");
        Files.createDirectories(repo.resolve("blobs"));
        Files.createSymbolicLink(repo.resolve("blobs/de"), outside);

        assertThat(BlobPaths.regularFile(repo, SHA)).isEmpty();
    }

    @Test
    void a_linked_blobs_directory_is_not_followed() throws IOException {
        Path outside = Files.createDirectories(repo.resolve("outside/de"));
        Files.writeString(outside.resolve(SHA), "x");
        Files.createSymbolicLink(repo.resolve("blobs"), outside.getParent());

        assertThat(BlobPaths.regularFile(repo, SHA)).isEmpty();
    }
}
