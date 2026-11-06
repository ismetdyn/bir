package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0023 decision 5 and ADR-0024 decision 4, SPEC §6.6, docs/MCP-TOOLS.md kb_read and kb_search:
 * every statement shows its source and status ({@code doğrulanmamış}, {@code ÇELİŞKİ}), and kb_read
 * names the client of the Episode it came from, on the current line and in history. Order is
 * Wikidata's rank: current and verified first, unverified after; nothing is hidden (invariant 12).
 */
class ProvenanceReadTest extends RunningServerTestBase {

    private static final String UNVERIFIED = "doğrulanmamış";

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path cwd;

    private EpisodeProbe probe;
    private McpClient mcp;

    @BeforeEach
    void open() throws Exception {
        probe = new EpisodeProbe(port, store);
        mcp = McpClient.open(port);
    }

    @Test
    void kb_read_names_the_client_of_the_source_episode_on_the_line_and_in_history()
            throws Exception {
        String entity = "zz-g12b-prov";
        Session claude = probe.start("claude", newSessionId(), cwd);
        Session codex = probe.start("codex", newSessionId(), cwd);
        remember(entity, "hostname", "zz-g12b-prov-a.ornek.example", "code", claude.episode());
        remember(entity, "hostname", "zz-g12b-prov-b.ornek.example", "code", codex.episode());
        remember(entity, "os", "rhel 9", "code", "");

        String read = mcp.text("kb_read", Map.of("id", entity));

        assertThat(line(read, "zz-g12b-prov-b.ornek.example"))
                .as("the current value came from the codex session")
                .contains("codex")
                .doesNotContain("claude");
        assertThat(line(read, "zz-g12b-prov-a.ornek.example"))
                .as("the closed value in history came from the claude session")
                .contains("claude");
        assertThat(line(read, "os: rhel 9"))
                .as("a fact without an episode names no client")
                .doesNotContain("claude", "codex");
    }

    @Test
    void kb_search_and_kb_read_lines_show_source_and_status() throws Exception {
        String entity = "zz-g12b-trust";
        String value = "kbtestzirkonyum kurulumu elle yapıldı";
        remember(entity, "note", value, "code", "");

        String found = mcp.search("kbtestzirkonyum", Map.of());
        String read = mcp.text("kb_read", Map.of("id", entity));

        assertThat(line(found, value)).contains("agent").contains(UNVERIFIED);
        assertThat(line(read, value)).contains("agent").contains(UNVERIFIED);
    }

    @Test
    void kb_read_lists_a_verified_statement_before_an_unverified_one() throws Exception {
        String entity = "zz-g12b-rank";
        String guessed = "zz-g12b-rank yedekleri hafta sonu alınıyor olabilir";
        String said = "zz-g12b-rank yedekleri her gece 02:00'de alınır";
        // No SessionStart: the rank does not depend on episodes, only on the user's prompt.
        Session unstarted = new Session("claude", newSessionId(), "", "");
        remember(entity, "note", guessed, "code", "");
        probe.prompt(unstarted, said);
        remember(entity, "note", said, "user_stated", "");

        String read = mcp.text("kb_read", Map.of("id", entity));

        String verified = line(read, said);
        String unverified = line(read, guessed);
        assertThat(verified).contains("user_stated").doesNotContain(UNVERIFIED);
        assertThat(unverified).contains("agent").contains(UNVERIFIED);
        assertThat(read.indexOf(verified))
                .as("verified before unverified:%n%s", read)
                .isLessThan(read.indexOf(unverified));
    }

    /** One fact; for user_stated the value is the user's words; an empty episode is left out. */
    private void remember(String entity, String key, String value, String kind, String episode)
            throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("entity", entity);
        arguments.put("type", "server");
        arguments.put("facts", List.of(Map.of("key", key, "value", value)));
        Map<String, String> source = new HashMap<>();
        source.put("kind", kind);
        if ("user_stated".equals(kind)) {
            source.put("evidence", value);
        }
        if (!episode.isEmpty()) {
            source.put("episode", episode);
        }
        arguments.put("source", source);
        assertThat(mcp.text("kb_remember", arguments)).doesNotStartWith("{\"error\"");
    }

    /** The one line of {@code text} holding {@code part}. */
    private static String line(String text, String part) {
        List<String> lines = text.lines().filter(line -> line.contains(part)).toList();
        assertThat(lines).as("one line with %s in:%n%s", part, text).hasSize(1);
        return lines.getFirst();
    }
}
