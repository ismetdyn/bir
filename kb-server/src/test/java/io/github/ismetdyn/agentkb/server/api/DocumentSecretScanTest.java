package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The document secret scan (ADR-0025 decision 3, SPEC §6.7, invariant 2): text decoded as UTF-8
 * with REPLACE, scanned with SecretPatterns in overlapping windows. A hit is 422 that names the
 * line and never the value; the staged bytes are removed and nothing reaches blobs/ or the graph.
 *
 * <p>The window size is not specified, so a token is placed across each power-of-two boundary from
 * 4 KiB to 1 MiB (also the PATCH part size): a scan of disjoint windows misses at least one.
 */
class DocumentSecretScanTest extends DocumentControllerTestBase {

    private static final String NAME = "zz-h4-secret.md";
    // A GitHub token shape (SecretPatterns), assembled so this file holds no token literal.
    private static final String TOKEN_TAIL = "kbtestFake".repeat(4).substring(0, 36);
    private static final String TOKEN = "ghp" + "_" + TOKEN_TAIL;
    private static final String FILLER = "plain runbook text line\n";
    private static final int SECRET_LINE = 4321;
    private static final int BEFORE_BOUNDARY = 20;

    @Autowired private WriteService writes;

    @Test
    void a_secret_in_the_text_is_refused_with_its_line_and_without_its_value() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line < SECRET_LINE; line++) {
            text.append(FILLER);
        }
        text.append("deploy token ").append(TOKEN).append('\n').append(UUID.randomUUID());
        byte[] content = text.toString().getBytes(StandardCharsets.UTF_8);

        assertRefusedAtLine(content, SECRET_LINE);
    }

    @Test
    void a_password_assignment_is_refused() throws Exception {
        byte[] content =
                ("zz-h4 config\n" + UUID.randomUUID() + "\nDB_PASSWORD=" + TOKEN_TAIL + "\n")
                        .getBytes(StandardCharsets.UTF_8);

        assertRefusedAtLine(content, 3);
    }

    /** Invalid UTF-8 is replaced, not a reason to stop scanning (decision 3). */
    @Test
    void a_secret_after_invalid_utf8_bytes_is_still_found() throws Exception {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xFE, '\n'});
        content.write(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        content.write(("\ntoken " + TOKEN + "\n").getBytes(StandardCharsets.UTF_8));

        assertRefusedAtLine(content.toByteArray(), 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {4096, 8192, 16384, 32768, 65536, 131072, 1048576})
    void a_secret_across_a_window_boundary_is_found(int boundary) throws Exception {
        String prefix =
                (UUID.randomUUID() + "\n" + FILLER.repeat(boundary / FILLER.length() + 1))
                        .substring(0, boundary - BEFORE_BOUNDARY);
        String text = prefix + " " + TOKEN + "\nend\n";
        int line = (int) prefix.chars().filter(c -> c == '\n').count() + 1;

        assertRefusedAtLine(text.getBytes(StandardCharsets.UTF_8), line);
    }

    /**
     * A value stored on a Secret is refused like a pattern hit, as on every other write path, even
     * when no pattern knows its shape; the answer names the line, never the value.
     */
    @Test
    void a_value_stored_on_a_secret_is_refused_with_its_line_and_without_its_value()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String stored = "zzStoredPlain" + suffix;
        assertThat(SecretPatterns.looksLikeSecret(stored)).as("no pattern finds it").isFalse();
        writes.putSecret(ENTITY + "/zz-h4/doc-scan-" + suffix.substring(0, 8), stored);
        byte[] content =
                ("zz-h4 notes\n" + UUID.randomUUID() + "\nthe login is " + stored + " today\n")
                        .getBytes(StandardCharsets.UTF_8);

        String sha = documents.upload(content);
        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(422);
        assertNoLeak(reply.body());
        assertThat(reply.body()).doesNotContain(stored).doesNotContain(suffix);
        assertThat(reply.body()).containsPattern(Pattern.compile("(?<![0-9])3(?![0-9])"));
        assertThat(stagedFiles(sha)).as("staging removed").isEmpty();
        assertThat(blobFiles(sha)).as("nothing in blobs/").isEmpty();
        assertThat(documentNodes(sha)).isEmpty();
    }

    /** F4: a stored value under four characters is no secret, so a document holding it passes. */
    @Test
    void a_stored_value_under_four_characters_does_not_refuse_a_document() throws Exception {
        writes.putSecret(ENTITY + "/zz-h4/doc-short", "42");
        String sha =
                documents.upload(
                        ("zz-h4 short\n" + UUID.randomUUID() + "\nport 42 and 4242\n")
                                .getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
    }

    /**
     * UTF-16 text, as PowerShell's {@code >} writes it, is scanned as UTF-16 (lead decision F3).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_secret_in_utf16le_text_is_refused(boolean byteOrderMark) throws Exception {
        String text =
                (byteOrderMark ? "\uFEFF" : "")
                        + "zz-h4 utf16\r\n"
                        + UUID.randomUUID()
                        + "\r\ndeploy token "
                        + TOKEN
                        + "\r\n";

        assertRefusedAtLine(text.getBytes(StandardCharsets.UTF_16LE), 3);
    }

    /** A clean document of the same shape passes, so the refusals above are the token's. */
    @Test
    void the_same_document_without_the_token_is_accepted() throws Exception {
        String text = (UUID.randomUUID() + "\n" + FILLER.repeat(3000)) + "deploy token none\n";
        String sha = documents.upload(text.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
    }

    private void assertRefusedAtLine(byte[] content, int line) throws Exception {
        String sha = documents.upload(content);

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(422);
        assertNoLeak(reply.body());
        assertThat(reply.body())
                .as("the value is never in the answer")
                .doesNotContain(TOKEN_TAIL)
                .doesNotContain("kbtestFake");
        assertThat(reply.body())
                .as("the answer names line %d", line)
                .containsPattern(Pattern.compile("(?<![0-9])" + line + "(?![0-9])"));
        assertThat(stagedFiles(sha)).as("staging removed").isEmpty();
        assertThat(blobFiles(sha)).as("nothing in blobs/").isEmpty();
        assertThat(documentNodes(sha)).isEmpty();
    }
}
