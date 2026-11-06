package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code kb import-memories} (docs/CLIENTS.md §2, docs/CURATOR.md §7, SPEC §11.5): reads Claude
 * Code auto memory, {@code ~/.claude/projects/<project>/memory/*.md}
 * (code.claude.com/docs/en/memory, 2026-11-06), and Codex Memories, every {@code .md} under {@code
 * ~/.codex/memories/} (openai/codex codex-rs memories: MEMORY.md, rollout_summaries/, extensions/),
 * and sends them in one {@code POST /api/v1/signals/imported-memories} {@code {"memories":
 * [{"source", "path", "text"}]}}, path relative to HOME. Transcripts, settings and global
 * instruction files are no memories. The server dedups by content hash (ImportedMemoriesApiTest),
 * so the client sends the same body every run. Exit codes as every maintenance command: 0, 1
 * refused, 3 unreachable.
 *
 * <p>HOME is a temporary directory (KbProcess passes it to the JVM as user.home too).
 */
class KbImportMemoriesTest {

    private static final String IMPORT = "/api/v1/signals/imported-memories";
    private static final int UNREACHABLE = 3;
    private static final Map<String, String> MEMORIES =
            Map.of(
                    ".claude/projects/-home-u-proj-a/memory/MEMORY.md",
                    "- [role](user_role.md) zz-claude-memory-index\n",
                    ".claude/projects/-home-u-proj-a/memory/user_role.md",
                    "zz-claude-memory-a the user is a backend developer\n",
                    ".claude/projects/-home-u-proj-b/memory/feedback.md",
                    "zz-claude-memory-b prefers short answers\n",
                    ".codex/memories/MEMORY.md",
                    "zz-codex-memory-index\n",
                    ".codex/memories/rollout_summaries/2026-11-01-x.md",
                    "zz-codex-memory-rollout weekly report format\n");
    private static final Map<String, String> NOT_MEMORIES =
            Map.of(
                    ".claude/projects/-home-u-proj-a/0f1e.jsonl", "zz-not-memory-transcript",
                    ".claude/CLAUDE.md", "zz-not-memory-claude-md",
                    ".claude/settings.json", "{\"zz-not-memory-settings\": true}",
                    ".codex/config.toml", "# zz-not-memory-codex-config\n",
                    ".codex/AGENTS.md", "zz-not-memory-agents-md");

    @TempDir Path home;
    @TempDir Path workDir;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void the_memories_of_both_clients_are_sent_in_one_request_and_nothing_else()
            throws IOException {
        write(MEMORIES);
        write(NOT_MEMORIES);
        server.answer("POST", IMPORT, 200, "{\"received\":5,\"opened\":4}");

        Result result = importMemories(server.url());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("5").contains("4");
        ApiStub.Request request = server.requests().getFirst();
        assertThat(server.requests()).hasSize(1);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.rawPath()).isEqualTo(IMPORT);
        for (String text : MEMORIES.values()) {
            assertThat(request.body()).contains(text.strip());
        }
        assertThat(request.body())
                .as("transcripts, settings, instructions")
                .doesNotContain("zz-not-memory");
        assertThat(request.body())
                .containsPattern(ApiStub.jsonField("source", "claude-code"))
                .containsPattern(ApiStub.jsonField("source", "codex"))
                .containsPattern(
                        ApiStub.jsonField(
                                "path", ".claude/projects/-home-u-proj-a/memory/user_role.md"))
                .containsPattern(
                        ApiStub.jsonField(
                                "path", ".codex/memories/rollout_summaries/2026-11-01-x.md"))
                .doesNotContain(home.toString());
    }

    @Test
    void a_second_run_sends_the_same_memories_again() throws IOException {
        write(MEMORIES);
        server.answer("POST", IMPORT, 200, "{\"received\":5,\"opened\":0}");

        Result first = importMemories(server.url());
        Result second = importMemories(server.url());

        assertThat(first.exitCode()).as(first.stderr()).isZero();
        assertThat(second.exitCode()).as(second.stderr()).isZero();
        List<ApiStub.Request> requests = server.requests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
    }

    @Test
    void a_home_without_memories_is_no_error() {
        server.answer("POST", IMPORT, 200, "{\"received\":0,\"opened\":0}");

        Result result = importMemories(server.url());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).as("says that nothing was found").isNotBlank();
    }

    @Test
    void a_refused_import_exits_one_with_the_error_and_the_hint() throws IOException {
        write(MEMORIES);
        server.answer(
                "POST",
                IMPORT,
                400,
                "{\"error\":\"zz-refused-error\",\"hint\":\"zz-refused-hint\"}");

        Result result = importMemories(server.url());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("zz-refused-error", "zz-refused-hint");
    }

    @Test
    void an_unreachable_server_exits_three() throws IOException {
        write(MEMORIES);
        String closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }

        Result result = importMemories(closed);

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(UNREACHABLE);
    }

    private Result importMemories(String url) {
        return KbProcess.run(url, Map.of("HOME", home.toString()), "", workDir, "import-memories");
    }

    private void write(Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = home.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
    }
}
