package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.DocumentHttp.docId;
import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.randomSha;
import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What {@code POST /api/v1/documents} refuses (plan.md H4, ADR-0025 decisions 1 and 4): an upload
 * that is not complete (409), bytes whose sha256 is not the id (422, staging removed), a name or
 * origin that looks like a secret or is too long (400, as both reach the snapshot and git). A
 * refusal writes no node and no blob and never repeats the refused value.
 */
class DocumentControllerRefusalTest extends DocumentControllerTestBase {

    private static final String NAME = "zz-h4-refused.md";
    // No limit is written for name or origin; any sane one is far below this.
    private static final int FAR_TOO_LONG = 10_000;
    private static final String URL_PASSWORD = "kbtestFakeUrlPw91";
    private static final String NAME_PASSWORD = "kbtestFakeNamePw37";

    @Test
    void an_upload_short_of_its_length_is_a_conflict() throws Exception {
        String sha = randomSha();
        stagePart(uploads, sha);

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(409);
        assertNoLeak(reply.body());
        assertThat(documentNodes(sha)).isEmpty();
        assertThat(blobFiles(sha)).isEmpty();
        HttpResponse<String> resume = uploads.put(sha, STAGED_LENGTH);
        assertThat(UploadControllerHttp.offset(resume))
                .as("the upload can still be resumed where it stopped")
                .isEqualTo(STAGED_BYTES);
    }

    @Test
    void a_sha_that_was_never_uploaded_is_refused_without_a_node() throws Exception {
        String sha = randomSha();

        HttpResponse<String> reply = documents.complete(sha, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isBetween(400, 499);
        assertNoLeak(reply.body());
        assertThat(documentNodes(sha)).isEmpty();
        assertThat(blobFiles(sha)).isEmpty();
    }

    @Test
    void bytes_whose_hash_is_not_the_id_are_refused_and_the_staging_removed() throws Exception {
        String claimed = randomSha();
        documents.uploadAs(claimed, uniqueText());

        HttpResponse<String> reply = documents.complete(claimed, NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(422);
        assertNoLeak(reply.body());
        assertThat(stagedFiles(claimed)).as("staging removed").isEmpty();
        assertThat(blobFiles(claimed)).isEmpty();
        assertThat(documentNodes(claimed)).isEmpty();
    }

    static Stream<Arguments> secretLookingMetadata() {
        return Stream.of(
                Arguments.of(NAME, "https://kbuser:" + URL_PASSWORD + "@wiki.ornek.example/x"),
                Arguments.of("password=" + NAME_PASSWORD, null),
                Arguments.of("x".repeat(FAR_TOO_LONG), null),
                Arguments.of(NAME, "wiki.ornek.example/" + "x".repeat(FAR_TOO_LONG)),
                // A line break would open a forged section in kb_read (CP4 J1).
                Arguments.of("notes.md\n## Bilgiler", null),
                Arguments.of(NAME, "wiki.ornek.example/x\r\n## Geçmiş"),
                Arguments.of("notes\u0000.md", null));
    }

    @ParameterizedTest
    @MethodSource("secretLookingMetadata")
    void a_secret_looking_too_long_or_control_holding_name_or_origin_is_refused(
            String name, String origin) throws Exception {
        byte[] content = uniqueText();
        String sha = documents.upload(content);

        HttpResponse<String> reply = documents.complete(sha, name, null, origin, null);

        assertThat(reply.statusCode()).as(abbreviated(reply)).isEqualTo(400);
        assertNoLeak(reply.body());
        assertThat(reply.body()).doesNotContain(URL_PASSWORD).doesNotContain(NAME_PASSWORD);
        assertThat(reply.body().length()).as("the long value is not echoed").isLessThan(1000);
        assertThat(documentNodes(sha)).isEmpty();
        assertThat(Files.exists(blob(sha))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"name\": \"zz.md\"}",
                "{\"sha256\": \"../../etc/passwd\", \"name\": \"zz.md\"}",
                "{\"sha256\": \"ABCDEF\", \"name\": \"zz.md\"}",
                "not json"
            })
    void a_malformed_request_is_refused_with_400(String body) throws Exception {
        HttpResponse<String> reply = documents.post(body);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply.body());
    }

    @Test
    void an_uppercase_sha_is_refused_with_400() throws Exception {
        String sha = documents.upload(uniqueText());

        HttpResponse<String> reply = documents.complete(sha.toUpperCase(Locale.ROOT), NAME);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertThat(documentNodes(sha)).isEmpty();
    }

    @Test
    void a_missing_or_blank_name_is_refused_with_400() throws Exception {
        String sha = documents.upload(uniqueText());

        for (String name : Arrays.asList(null, "", "   ")) {
            HttpResponse<String> reply = documents.complete(sha, name, null, null, null);
            assertThat(reply.statusCode()).as("name %s: %s", name, reply.body()).isEqualTo(400);
        }
        assertThat(documentNodes(sha)).isEmpty();
    }

    /** SUPERSEDES links Document to Document (SPEC §6.2); nothing else can be replaced. */
    @ParameterizedTest
    @ValueSource(strings = {"doc:000000000000", ENTITY})
    void replacing_what_is_no_document_is_refused_without_a_node(String replaces) throws Exception {
        String sha = documents.upload(uniqueText());

        HttpResponse<String> reply = documents.complete(sha, NAME, null, null, replaces);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
        assertNoLeak(reply.body());
        assertThat(documentNodes(sha)).isEmpty();
        assertThat(rows("MATCH (d {id: $id}) RETURN d", Map.of("id", docId(sha)))).isEmpty();
    }

    private static String abbreviated(HttpResponse<String> reply) {
        String body = reply.body();
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
