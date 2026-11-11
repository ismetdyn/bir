package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb sync} sets the union merge of {@code .gitattributes} in {@code .git/info/attributes}
 * (ADR-0025 decision 5), a file the user may have written too: their bytes stay as they are, in
 * whatever encoding, and the line is only appended, once.
 */
class DocumentStoreGitTest {

    private static final byte[] UNION_LINE =
            "/.gitattributes merge=union\n".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] LINE_ENDINGS =
            "snapshot/*.jsonl text eol=lf\nblobs/** -text\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path repo;

    @Test
    void the_users_lines_are_kept_byte_for_byte_and_the_line_appended_once() throws IOException {
        Path attributes = Files.createDirectories(repo.resolve(".git/info")).resolve("attributes");
        // Latin-1 é (0xE9) is no valid UTF-8; no newline at the end.
        byte[] user = {
            '*', '.', 'b', 'i', 'n', ' ', '-', 't', 'e', 'x', 't', '\n', '#', (byte) 0xE9
        };
        Files.write(attributes, user);

        new DocumentStoreGit(repo).mergeByUnion();
        new DocumentStoreGit(repo).mergeByUnion();

        assertThat(Files.readAllBytes(attributes)).isEqualTo(concat(user, "\n", UNION_LINE));
    }

    @Test
    void a_missing_file_gets_the_line_alone() throws IOException {
        new DocumentStoreGit(repo).mergeByUnion();

        assertThat(Files.readAllBytes(repo.resolve(".git/info/attributes"))).isEqualTo(UNION_LINE);
    }

    @Test
    void a_linked_attributes_file_is_refused_and_its_target_is_untouched(@TempDir Path elsewhere)
            throws IOException {
        Path victim = Files.writeString(elsewhere.resolve("victim"), "the user's own file\n");
        Path info = Files.createDirectories(repo.resolve(".git/info"));
        Files.createSymbolicLink(info.resolve("attributes"), victim);

        assertThatThrownBy(() -> new DocumentStoreGit(repo).mergeByUnion())
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining(elsewhere.toString());

        assertThat(Files.readString(victim)).isEqualTo("the user's own file\n");
    }

    /**
     * ADR-0027 decision 8: the tracked {@code .gitattributes} keeps the snapshot LF and the blobs
     * byte for byte on a Windows clone with {@code core.autocrlf}. Appended after the user's (and
     * git-lfs's) lines, each once, whatever is already there.
     */
    @Test
    void the_line_ending_attributes_are_appended_once_after_the_lines_already_there()
            throws IOException {
        Path attributes = repo.resolve(".gitattributes");
        byte[] lfs =
                "blobs/ab/cd filter=lfs diff=lfs merge=lfs -text"
                        .getBytes(StandardCharsets.US_ASCII);
        Files.write(attributes, lfs);

        new DocumentStoreGit(repo).keepLineEndings();
        new DocumentStoreGit(repo).keepLineEndings();

        assertThat(Files.readAllBytes(attributes)).isEqualTo(concat(lfs, "\n", LINE_ENDINGS));
    }

    @Test
    void only_a_missing_line_ending_attribute_is_added() throws IOException {
        Path attributes = Files.writeString(repo.resolve(".gitattributes"), "blobs/** -text\n");

        new DocumentStoreGit(repo).keepLineEndings();

        assertThat(attributes).hasContent("blobs/** -text\nsnapshot/*.jsonl text eol=lf\n");
    }

    @Test
    void a_linked_gitattributes_is_refused_and_its_target_is_untouched(@TempDir Path elsewhere)
            throws IOException {
        Path victim = Files.writeString(elsewhere.resolve("victim"), "the user's own file\n");
        Files.createSymbolicLink(repo.resolve(".gitattributes"), victim);

        assertThatThrownBy(() -> new DocumentStoreGit(repo).keepLineEndings())
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining(elsewhere.toString());

        assertThat(Files.readString(victim)).isEqualTo("the user's own file\n");
    }

    /**
     * A repository made before ADR-0027 gets the attributes with its next sync, and git uses them.
     */
    @Test
    void a_sync_stages_the_line_ending_attributes_and_git_applies_them() throws IOException {
        assertThat(SystemGit.run(repo, false, "init", "--quiet").ok()).isTrue();

        new SyncGit(repo).stage(new SnapshotFormat.Snapshot(List.of(), List.of()));

        assertThat(SystemGit.run(repo, false, "diff", "--cached", "--name-only").output().lines())
                .contains(".gitattributes");
        assertThat(
                        SystemGit.run(
                                        repo,
                                        false,
                                        "check-attr",
                                        "text",
                                        "eol",
                                        "--",
                                        "snapshot/nodes.jsonl",
                                        "blobs/ab/cdef")
                                .output()
                                .lines())
                .containsExactly(
                        "snapshot/nodes.jsonl: text: set",
                        "snapshot/nodes.jsonl: eol: lf",
                        "blobs/ab/cdef: text: unset",
                        "blobs/ab/cdef: eol: unspecified");
    }

    private static byte[] concat(byte[] head, String separator, byte[] tail) {
        byte[] middle = separator.getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[head.length + middle.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(middle, 0, all, head.length, middle.length);
        System.arraycopy(tail, 0, all, head.length + middle.length, tail.length);
        return all;
    }
}
