package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.EpisodeProbe.NONE;
import static io.github.ismetdyn.agentkb.server.EpisodeProbe.newSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.EpisodeProbe;
import io.github.ismetdyn.agentkb.server.EpisodeProbe.Session;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0023 decisions 2 and 3, docs/MCP-TOOLS.md kb_remember and kb_propose: the optional episode
 * ({@code source.episode} for kb_remember, the {@code episode} argument for kb_propose) is stamped
 * on every node and edge the write opens. An unknown or missing episode is not an error; the
 * records then carry none. kb_scan: EpisodeSourcesTest.
 */
class EpisodeWriteTest extends RunningServerTestBase {

    private static final Pattern PROPOSAL_ID = Pattern.compile("Proposal (\\S+) is open");
    // A seed Fact id (seed/README.md): a record that exists but is no Episode.
    private static final String SEED_FACT = "01KF0E44M0JB0CE671YFMPJS8A";

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
    void kb_remember_stamps_the_episode_on_every_node_and_edge_it_opens() throws Exception {
        Session session = probe.start("claude", newSessionId(), cwd);
        remember("zz-g12b-stamp-peer", List.of(Map.of("key", "os", "value", "rhel 9")), "");

        String reply =
                mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity", "zz-g12b-stamp",
                                "type", "server",
                                "facts",
                                        List.of(
                                                Map.of("key", "os", "value", "rhel 9"),
                                                Map.of("key", "note", "value", "zz yedek sunucu")),
                                "relations",
                                        List.of(
                                                Map.of(
                                                        "type", "depends_on",
                                                        "target", "zz-g12b-stamp-peer")),
                                "source", Map.of("kind", "code", "episode", session.episode())));

        // The entity, two facts, two HAS_FACT edges and the relation at least.
        assertThat(probe.stamps(EpisodeProbe.batch(reply)))
                .as(reply)
                .hasSizeGreaterThanOrEqualTo(6)
                .containsOnly(session.episode());
    }

    @Test
    void kb_propose_stamps_the_episode_on_the_proposal_and_its_about_edge() throws Exception {
        Session session = probe.start("codex", newSessionId(), cwd);
        remember("zz-g12b-propose-target", List.of(Map.of("key", "os", "value", "rhel 9")), "");

        String reply =
                mcp.text(
                        "kb_propose",
                        Map.of(
                                "kind", "archive",
                                "target", "zz-g12b-propose-target",
                                "content", "Arşivle: zz-g12b hedef kullanılmıyor.",
                                "episode", session.episode()));

        Matcher id = PROPOSAL_ID.matcher(reply);
        assertThat(id.find()).as(reply).isTrue();
        assertThat(
                        probe.rows(
                                "MATCH (p:Proposal {id: $id})-[r:ABOUT]->()"
                                        + " RETURN p.episode AS node, r.episode AS edge",
                                Map.of("id", id.group(1))))
                .singleElement()
                .isEqualTo(Map.of("node", session.episode(), "edge", session.episode()));
    }

    /** ADR-0023 decision 3: "Bilinmeyen ya da verilmeyen episode hata değildir". */
    @ParameterizedTest(name = "episode ''{1}''")
    @CsvSource({"1, 01KZZZZZZZZZZZZZZZZZZZZZZZ", "2, no-such-episode", "3, " + SEED_FACT, "4, ''"})
    void an_unknown_or_empty_episode_is_ignored_and_the_write_is_stored(String row, String episode)
            throws Exception {
        String reply =
                remember(
                        "zz-g12b-unknown-" + row,
                        List.of(Map.of("key", "os", "value", "rhel 9")),
                        episode);

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertThat(probe.stamps(EpisodeProbe.batch(reply))).isNotEmpty().containsOnly(NONE);
        if (!episode.isEmpty()) {
            assertThat(probe.episode(episode)).as("no Episode is made up").isEmpty();
        }
    }

    /**
     * docs/MCP-TOOLS.md kb_remember: the episode belongs to {@code source}; the tool has no
     * top-level {@code episode} input. Like any key outside the input schema it is ignored: the
     * write is stored and carries no episode, even when the id names a real Episode.
     */
    @Test
    void kb_remember_ignores_a_top_level_episode_as_provenance() throws Exception {
        Session session = probe.start("claude", newSessionId(), cwd);

        String reply =
                mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity", "zz-g12b-top-level",
                                "type", "server",
                                "facts", List.of(Map.of("key", "os", "value", "rhel 9")),
                                "source", Map.of("kind", "code"),
                                "episode", session.episode()));

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertThat(probe.stamps(EpisodeProbe.batch(reply))).isNotEmpty().containsOnly(NONE);
    }

    /** An empty {@code episode} stands for "not given"; every other value is passed on. */
    private String remember(String entity, List<Map<String, String>> facts, String episode)
            throws Exception {
        return episode.isEmpty()
                ? mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                entity,
                                "type",
                                "server",
                                "facts",
                                facts,
                                "source",
                                Map.of("kind", "code")))
                : mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                entity,
                                "type",
                                "server",
                                "facts",
                                facts,
                                "source",
                                Map.of("kind", "code", "episode", episode)));
    }
}
