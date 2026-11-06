package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.offset;
import static io.github.ismetdyn.agentkb.server.api.UploadControllerHttp.randomSha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.ismetdyn.agentkb.server.AgentkbServerApplication;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Upload staging in a server of its own JVM (plan.md H3, ADR-0025 decision 1):
 *
 * <ul>
 *   <li>Under {@code -Xmx64m} a 200-part upload of 1 MiB parts (200 MiB, three times the heap) ends
 *       without OutOfMemoryError: the bodies go to disk, not to the heap. This also shows that
 *       Tomcat does not read an {@code application/offset+octet-stream} body ahead.
 *   <li>Staged files idle for more than 7 days are removed when the server starts, before any
 *       request.
 * </ul>
 *
 * 200 MiB is over 20 MB, so the PUT needs git-lfs (ADR-0025 decision 5). The child gets a stand-in
 * {@code git-lfs} first on its PATH that only answers successfully: staging never runs git-lfs, it
 * only asks whether it is there (tracking is H4, at POST /documents). HOME, user.home and
 * GIT_CONFIG_GLOBAL point into the work dir, so the child never touches this machine's settings.
 */
class UploadControllerLowHeapTest {

    private static final int MIB = 1024 * 1024;
    private static final int PARTS = 200;
    private static final long TOTAL = (long) PARTS * MIB;
    private static final Duration AGED = Duration.ofDays(8);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Path SEED = Path.of("..", "seed");
    private static final Map<String, List<String>> SEED_FILES =
            Map.of(
                    "snapshot", List.of("nodes.jsonl", "edges.jsonl"),
                    "schema", List.of("types.yaml", "relations.yaml", "facts.yaml"));

    private static Path work;
    private static ChildServer child;

    @BeforeAll
    static void startChild() throws IOException, InterruptedException {
        Path target = Files.createDirectories(Path.of("target"));
        work = Files.createTempDirectory(target, "upload-low-heap-").toAbsolutePath();
        for (Map.Entry<String, List<String>> dir : SEED_FILES.entrySet()) {
            Path copy = Files.createDirectories(work.resolve("repo").resolve(dir.getKey()));
            for (String file : dir.getValue()) {
                Files.copy(SEED.resolve(dir.getKey()).resolve(file), copy.resolve(file));
            }
        }
        Files.createDirectories(work.resolve("home"));
        Path gitLfs = Files.createDirectories(work.resolve("bin")).resolve("git-lfs");
        Files.writeString(
                gitLfs,
                "#!/bin/sh\necho 'git-lfs/0.0.0 (UploadControllerLowHeapTest stand-in)'\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(gitLfs, PosixFilePermissions.fromString("rwxr-xr-x"));
        child = ChildServer.start(work);
    }

    @AfterAll
    static void stopChild() throws InterruptedException {
        if (child != null) {
            child.close();
        }
    }

    @Test
    void two_hundred_one_mib_parts_upload_in_a_64_mb_heap() throws Exception {
        UploadControllerHttp uploads = new UploadControllerHttp(child.port);
        String sha = sha256OfParts();
        HttpResponse<String> created = uploads.put(sha, TOTAL);
        assertThat(created.statusCode()).as(child.describe(created)).isEqualTo(201);

        for (int part = 0; part < PARTS; part++) {
            long at = (long) part * MIB;
            HttpResponse<String> reply = uploads.patch(sha, at, part(part));

            assertThat(reply.statusCode())
                    .as("part %d: %s", part, child.describe(reply))
                    .isEqualTo(204);
            assertThat(offset(reply)).as("part %d", part).isEqualTo(at + MIB);
        }

        assertThat(child.process.isAlive()).as(child.output()).isTrue();
        assertThat(child.output()).doesNotContain("OutOfMemoryError");
        HttpResponse<String> resumed = uploads.put(sha, TOTAL);
        assertThat(resumed.statusCode()).as(child.describe(resumed)).isEqualTo(200);
        assertThat(offset(resumed)).isEqualTo(TOTAL);
        Path staging = work.resolve("data").resolve("uploads");
        assertThat(UploadControllerTestBase.filesUnder(staging))
                .as("one staged file holds all %d bytes", TOTAL)
                .anySatisfy(file -> assertThat(Files.size(file)).isEqualTo(TOTAL));
        assertThat(uploads.delete(sha).statusCode()).isEqualTo(204);
        assertThat(UploadControllerTestBase.filesUnder(staging))
                .noneSatisfy(file -> assertThat(Files.size(file)).isEqualTo(TOTAL));
    }

    @Test
    void startup_removes_staged_files_idle_for_more_than_seven_days() throws Exception {
        Path staging = work.resolve("data").resolve("uploads");
        UploadControllerHttp before = new UploadControllerHttp(child.port);
        String idle = randomSha();
        Set<Path> idleFiles =
                UploadControllerTestBase.stagedBy(
                        staging, () -> UploadControllerTestBase.stagePart(before, idle));
        String fresh = randomSha();
        Set<Path> freshFiles =
                UploadControllerTestBase.stagedBy(
                        staging, () -> UploadControllerTestBase.stagePart(before, fresh));
        assertThat(idleFiles).as("the idle upload left files in %s", staging).isNotEmpty();
        assertThat(freshFiles).as("the fresh upload left files in %s", staging).isNotEmpty();
        child.close();
        FileTime longAgo = FileTime.from(Instant.now().minus(AGED));
        for (Path file : idleFiles) {
            Files.setLastModifiedTime(file, longAgo);
        }

        child = ChildServer.start(work);

        awaitGone(idleFiles);
        assertThat(freshFiles).allSatisfy(file -> assertThat(file).exists());
        UploadControllerHttp after = new UploadControllerHttp(child.port);
        assertThat(offset(after.put(fresh, 10))).as("the fresh upload goes on").isEqualTo(4);
        assertThat(after.put(idle, 10).statusCode()).as("the idle one starts over").isEqualTo(201);
    }

    /** No request is sent while waiting: only startup may remove them. */
    private static void awaitGone(Set<Path> files) throws InterruptedException {
        Instant deadline = Instant.now().plus(CLEANUP_TIMEOUT);
        while (Instant.now().isBefore(deadline) && files.stream().anyMatch(Files::exists)) {
            Thread.sleep(POLL_INTERVAL);
        }
        assertThat(files).allSatisfy(file -> assertThat(file).doesNotExist());
    }

    /** Part {@code index}, the same bytes every time it is made. */
    private static byte[] part(int index) {
        byte[] bytes = new byte[MIB];
        new Random(index).nextBytes(bytes);
        return bytes;
    }

    private static String sha256OfParts() throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int part = 0; part < PARTS; part++) {
            digest.update(part(part));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The application in its own JVM with a 64 MB heap that exits on OutOfMemoryError. */
    private static final class ChildServer implements AutoCloseable {

        private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
        private static final int STOP_TIMEOUT_SECONDS = 30;
        private static final int TAIL_CHARS = 2000;
        private static final Pattern STARTED = Pattern.compile("Tomcat started on port (\\d+)");

        private final Process process;
        private final Path output;
        private final int port;

        private ChildServer(Process process, Path output, int port) {
            this.process = process;
            this.output = output;
            this.port = port;
        }

        static ChildServer start(Path work) throws IOException, InterruptedException {
            Path output = Files.createTempFile(work, "child-", ".log");
            Path home = work.resolve("home");
            ProcessBuilder builder =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-Xmx64m",
                                    "-XX:+ExitOnOutOfMemoryError",
                                    "-Duser.home=" + home,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    AgentkbServerApplication.class.getName(),
                                    "--server.port=0",
                                    "--agentkb.data-dir=" + work.resolve("data"),
                                    "--agentkb.repo=" + work.resolve("repo"))
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            Map<String, String> env = builder.environment();
            env.put("HOME", home.toString());
            env.put("GIT_CONFIG_GLOBAL", home.resolve(".gitconfig").toString());
            env.put("PATH", work.resolve("bin") + ":" + env.getOrDefault("PATH", ""));
            Process process = builder.start();
            Instant deadline = Instant.now().plus(START_TIMEOUT);
            while (Instant.now().isBefore(deadline) && process.isAlive()) {
                Matcher started = STARTED.matcher(Files.readString(output, StandardCharsets.UTF_8));
                if (started.find()) {
                    return new ChildServer(process, output, Integer.parseInt(started.group(1)));
                }
                Thread.sleep(POLL_INTERVAL);
            }
            stop(process);
            return fail(
                    "the child server did not start: %s",
                    Files.readString(output, StandardCharsets.UTF_8));
        }

        String output() throws IOException {
            String text = Files.readString(output, StandardCharsets.UTF_8);
            return text.length() <= TAIL_CHARS ? text : text.substring(text.length() - TAIL_CHARS);
        }

        String describe(HttpResponse<String> reply) throws IOException {
            return reply.body() + "\nchild alive: " + process.isAlive() + "\n" + output();
        }

        @Override
        public void close() throws InterruptedException {
            stop(process);
        }

        private static void stop(Process process) throws InterruptedException {
            process.destroy();
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
