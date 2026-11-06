package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.api.UploadControllerHttp;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.write.SyncMachines.Machine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb sync} carries the document store (ADR-0025 decision 5, ADR-0017 decision 2 and 7,
 * plan.md H4): besides {@code snapshot/} it commits {@code blobs/} and {@code .gitattributes}, so a
 * blob added on one machine arrives byte for byte on another; files the user staged elsewhere stay
 * out of the commit (SyncScopeTest); a failed git-lfs push is a sync error.
 *
 * <p>The blob is placed where POST /documents leaves it; the node path itself is covered over HTTP
 * (DocumentControllerAddTest). A graph write makes the snapshot change, as a new Document would.
 */
class SyncBlobsTest {

    private static final Instant T1 = Instant.parse("2026-11-01T09:00:00Z");
    private static final String ATTRIBUTES = ".gitattributes";
    private static final String ATTRIBUTE_LINE = "blobs/** -text\n";
    private static final String FOREIGN = "notes.txt";
    // Nothing listens on port 1 of the loopback, so the LFS upload fails without a network.
    private static final String DEAD_LFS_URL = "http://127.0.0.1:1/lfs";

    @TempDir Path dir;

    private SyncMachines machines;

    @AfterEach
    void closeMachines() {
        if (machines != null) {
            machines.close();
        }
    }

    @Test
    void a_blob_added_on_one_machine_arrives_byte_for_byte_on_another() throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        Machine b = machines.clone("b");
        byte[] content = binaryContent();
        String path = putBlob(a.repo, content);
        Files.writeString(a.repo.resolve(ATTRIBUTES), ATTRIBUTE_LINE, StandardCharsets.UTF_8);
        a.graph.write(request(Source.USER, "zz-sync-blob", T1, fact("os", "rhel 9", PROD)));

        SyncResult first = a.sync();
        b.sync();

        assertThat(first.pushed()).isTrue();
        assertThat(changedIn(a.repo, "HEAD"))
                .as("files in A's sync commit")
                .contains(path, ATTRIBUTES, "snapshot/nodes.jsonl");
        assertThat(ScanProjects.git(machines.remote, "ls-tree", "-r", "--name-only", "main"))
                .contains(path)
                .contains(ATTRIBUTES);
        assertThat(Files.readAllBytes(b.repo.resolve(path)))
                .as("the blob on machine B")
                .isEqualTo(content);
        assertThat(Files.readString(b.repo.resolve(ATTRIBUTES))).isEqualTo(ATTRIBUTE_LINE);
        assertThat(ScanProjects.git(a.repo, "status", "--porcelain", "--", "blobs"))
                .as("nothing under blobs/ left uncommitted on A")
                .isBlank();
    }

    @Test
    void a_blob_is_committed_and_a_foreign_staged_file_stays_staged() throws IOException {
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        String path = putBlob(a.repo, binaryContent());
        Files.writeString(a.repo.resolve(FOREIGN), "the user's own note\n", StandardCharsets.UTF_8);
        ScanProjects.git(a.repo, "add", "--", FOREIGN);
        a.graph.write(request(Source.USER, "zz-sync-blob-scope", T1, fact("os", "rhel 9", PROD)));

        a.sync();

        assertThat(changedIn(a.repo, "HEAD")).contains(path).doesNotContain(FOREIGN);
        assertThat(ScanProjects.git(a.repo, "diff", "--cached", "--name-only").lines())
                .containsExactly(FOREIGN);
    }

    /** ADR-0017 decision 7: "LFS gönderimi başarısızsa kb sync hata verir". */
    @Test
    void a_failed_git_lfs_push_is_a_sync_error() throws IOException, InterruptedException {
        Assumptions.assumeTrue(UploadControllerHttp.gitLfsPresent(), "not run: git-lfs missing");
        machines = SyncMachines.withSeedRemote(dir);
        Machine a = machines.clone("a");
        String remoteHead = machines.remoteHead();
        String path = putBlob(a.repo, binaryContent());
        ScanProjects.git(a.repo, "lfs", "install", "--local");
        ScanProjects.git(a.repo, "lfs", "track", path);
        ScanProjects.git(a.repo, "config", "--local", "lfs.url", DEAD_LFS_URL);
        a.graph.write(request(Source.USER, "zz-sync-lfs", T1, fact("os", "rhel 9", PROD)));

        WriteRejectedException refused =
                catchThrowableOfType(WriteRejectedException.class, a::sync);

        assertThat(refused).as("the sync reported no error").isNotNull();
        assertThat(refused.hint()).isNotBlank();
        assertThat(machines.remoteHead()).as("nothing pushed").isEqualTo(remoteHead);
    }

    /** Bytes that are no valid text, so a line-ending or text filter would change them. */
    private static byte[] binaryContent() {
        byte[] marker = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[marker.length + 6];
        System.arraycopy(marker, 0, content, 0, marker.length);
        byte[] tail = {'\r', '\n', 0, (byte) 0xFF, '\r', '\n'};
        System.arraycopy(tail, 0, content, marker.length, tail.length);
        return content;
    }

    /** Writes {@code content} as POST /documents does; returns its repo-relative path. */
    private static String putBlob(Path repo, byte[] content) throws IOException {
        String sha = sha256(content);
        String path = "blobs/" + sha.substring(0, 2) + "/" + sha;
        Path blob = repo.resolve(path);
        Files.createDirectories(blob.getParent());
        Files.write(blob, content);
        return path;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> changedIn(Path repo, String commit) {
        return ScanProjects.git(repo, "show", "--name-only", "--format=", commit)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();
    }
}
