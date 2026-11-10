package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.heading;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.section;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;

/**
 * kb_read of a document (docs/MCP-TOOLS.md kb_read, plan.md H7, seed/README.md "Doküman"): its
 * metadata, the full sha256, the absolute path of its blob, what it is about and the facts that
 * came from it (FROM), rendered by MarkdownView. An unknown document is {@code {error, hint}}.
 */
class DocumentReadToolTest extends RunningServerTestBase {

    static final String SEED_DOC = "doc:ded48257b6f3";
    static final String SEED_SHA =
            "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf4";

    @Value("${agentkb.repo}")
    private String repo;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void read_of_the_seed_document_gives_its_path_sha_about_and_facts() throws Exception {
        String text = mcp.text("kb_read", Map.of("id", SEED_DOC));

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        assertThat(heading(text))
                .as(text)
                .hasValueSatisfying(line -> assertThat(line).contains(SEED_DOC, "Document"));
        String absolute = Path.of(repo).resolve("blobs/de/" + SEED_SHA).toAbsolutePath().toString();
        assertThat(text)
                .contains(SEED_SHA)
                .contains(absolute)
                .contains("ornek-proje-calistirma.md")
                .contains("wiki.ornek.example/ornek-proje/calistirma");
        assertThat(section(text, "Hakkında")).as(text).contains("- ornek-proje");
        assertThat(section(text, "Bilgiler"))
                .as(text)
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains(
                                                "ornek-proje",
                                                "convention: Yeniden başlatma",
                                                "agent",
                                                "doğrulanmamış"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc:000000000000", "doc:../../etc", "doc:"})
    void an_unknown_document_is_an_error_with_a_hint(String id) throws Exception {
        String text = mcp.text("kb_read", Map.of("id", id));

        assertErrorWithHint(text);
    }
}
