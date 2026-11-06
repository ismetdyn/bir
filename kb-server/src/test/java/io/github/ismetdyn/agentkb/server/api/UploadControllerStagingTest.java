package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.offset;
import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.randomSha;
import static io.github.ismetdyn.agentkb.server.security.ErrorBodyAssertions.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where staged bytes may go and how long they stay: only a lowercase sha256 names an upload, path
 * traversal writes nothing, DELETE discards, and a PUT removes uploads idle for more than 7 days
 * (UploadControllerTestBase; startup cleanup is in UploadControllerLowHeapTest).
 */
class UploadControllerStagingTest extends UploadControllerTestBase {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567890",
                "gbcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef012345678.",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789.tmp"
            })
    void id_that_is_not_lowercase_sha256_hex_is_refused_by_every_method(String id)
            throws Exception {
        List<HttpResponse<String>> replies =
                List.of(
                        uploads.put(id, STAGED_LENGTH),
                        uploads.patch(id, 0, new byte[1]),
                        uploads.delete(id));

        assertThat(replies)
                .allSatisfy(
                        reply -> {
                            assertThat(reply.statusCode()).as(reply.body()).isEqualTo(400);
                            assertNoLeak(reply.body());
                        });
    }

    /**
     * Traversal attempts may be refused by Tomcat, by routing or by the controller; whichever layer
     * answers, nothing named after the marker may appear anywhere under the work dir that holds the
     * data dir and the repo.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "..%2F..%2F{marker}",
                "%2e%2e%2f%2e%2e%2f{marker}",
                "..%5C..%5C{marker}",
                "{marker}%00",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789%2F..%2F..%2F{marker}",
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789%00{marker}"
            })
    void path_traversal_writes_nothing_outside_staging(String template) throws Exception {
        String marker = "escaped-" + UUID.randomUUID();
        String id = template.replace("{marker}", marker);

        List<HttpResponse<String>> replies =
                List.of(
                        uploads.put(id, STAGED_LENGTH),
                        uploads.patch(id, 0, new byte[STAGED_LENGTH]));

        assertThat(replies)
                .allSatisfy(
                        reply -> {
                            assertThat(reply.statusCode()).as(reply.body()).isIn(400, 404);
                            assertNoLeak(reply.body());
                        });
        try (Stream<Path> files = Files.walk(dataDir.toAbsolutePath().getParent())) {
            assertThat(files.filter(file -> file.getFileName().toString().contains(marker)))
                    .isEmpty();
        }
    }

    @Test
    void delete_discards_the_upload() throws Exception {
        String sha = randomSha();
        stagePart(uploads, sha);

        HttpResponse<String> reply = uploads.delete(sha);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(204);
        HttpResponse<String> again = uploads.put(sha, STAGED_LENGTH);
        assertThat(again.statusCode()).as("a new upload again").isEqualTo(201);
        assertThat(offset(again)).isZero();
    }

    @Test
    void put_removes_staged_files_idle_for_more_than_seven_days() throws Exception {
        String idle = randomSha();
        Set<Path> idleFiles = stagedBy(staging(), () -> stagePart(uploads, idle));
        String fresh = randomSha();
        Set<Path> freshFiles = stagedBy(staging(), () -> stagePart(uploads, fresh));
        assertThat(idleFiles).as("the idle upload left files in %s", staging()).isNotEmpty();
        assertThat(freshFiles).as("the fresh upload left files in %s", staging()).isNotEmpty();
        FileTime longAgo = FileTime.from(Instant.now().minus(AGED));
        for (Path file : idleFiles) {
            Files.setLastModifiedTime(file, longAgo);
        }

        HttpResponse<String> reply = uploads.put(randomSha(), STAGED_LENGTH);

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
        assertThat(idleFiles).allSatisfy(file -> assertThat(file).doesNotExist());
        assertThat(freshFiles).allSatisfy(file -> assertThat(file).exists());
        assertThat(offset(uploads.put(fresh, STAGED_LENGTH)))
                .as("the fresh upload goes on")
                .isEqualTo(STAGED_BYTES);
        assertThat(uploads.put(idle, STAGED_LENGTH).statusCode())
                .as("the idle one starts over")
                .isEqualTo(201);
    }
}
