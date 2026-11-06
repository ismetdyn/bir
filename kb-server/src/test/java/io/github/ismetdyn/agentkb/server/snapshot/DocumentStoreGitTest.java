package io.github.ismetdyn.agentkb.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static byte[] concat(byte[] head, String separator, byte[] tail) {
        byte[] middle = separator.getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[head.length + middle.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(middle, 0, all, head.length, middle.length);
        System.arraycopy(tail, 0, all, head.length + middle.length, tail.length);
        return all;
    }
}
