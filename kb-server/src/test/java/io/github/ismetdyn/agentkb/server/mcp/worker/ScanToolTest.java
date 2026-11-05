package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertNoInternalDetail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * kb_scan over the real /mcp endpoint (docs/MCP-TOOLS.md, ARCHITECTURE §6). It writes, so it is not
 * announced read-only; it reads only under agentkb.scan.allowed-roots (invariant 9) and answers
 * {@code {error, hint}} for anything else, writing nothing. Every project here is zz-g10-*, so the
 * shared seed graph of the other server tests keeps its records.
 */
class ScanToolTest extends RunningServerTestBase {

    // Not the fixture's values: those belong to SecretLeakIT, which runs in its own JVM.
    private static final String PROD_VALUE = "kbtest-fake-6e2d0a9f3c71b845";
    private static final String LOCAL_VALUE = "kbtest-fake-91c4e07b2a5d38f6";

    @Autowired private EmbeddedNeo4jStore store;

    @TempDir Path outside;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void kb_scan_is_announced_as_writing_but_not_destructive_and_takes_a_path() throws Exception {
        JsonNode tool = null;
        for (JsonNode candidate : mcp.toolsList().at("/result/tools")) {
            if ("kb_scan".equals(candidate.path("name").asString(""))) {
                tool = candidate;
            }
        }

        assertThat(tool).as("kb_scan in tools/list").isNotNull();
        assertThat(tool.at("/annotations/readOnlyHint").asBoolean(true))
                .as(tool.toString())
                .isFalse();
        assertThat(tool.at("/annotations/destructiveHint").asBoolean(true))
                .as(tool.toString())
                .isFalse();
        assertThat(tool.at("/annotations/openWorldHint").asBoolean(true))
                .as(tool.toString())
                .isFalse();
        assertThat(tool.at("/inputSchema/properties/path").isMissingNode())
                .as(tool.toString())
                .isFalse();
    }

    @Test
    void a_scan_reports_new_entities_placeholders_and_findings_but_no_value() throws Exception {
        String name = "zz-g10-tool";
        Path project = ScanProjects.springProject(scanRoot(), name, PROD_VALUE, LOCAL_VALUE);
        String prodSecret = name + "/prod/spring.datasource.password";

        String report = mcp.text("kb_scan", Map.of("path", project.toString()));

        assertThat(report).doesNotStartWith("{\"error\"");
        assertNoInternalDetail(report);
        assertThat(report)
                .contains(name + "-db")
                .contains("kb:secret/" + prodSecret)
                .contains("application-prod.yml:" + ScanProjects.PASSWORD_LINE)
                .doesNotContain(PROD_VALUE, LOCAL_VALUE);
        assertThat(mcp.text("kb_read", Map.of("id", name + "-db")))
                .contains(name + "-db.ornek.example")
                .contains("scan");
        assertThat(new SecretHttp(port).get(prodSecret).body())
                .as("the value is on this machine, under its placeholder id")
                .isEqualTo(PROD_VALUE);
        assertThat(new SecretHttp(port).get(name + "/local/ORACLE_PASSWORD").body())
                .isEqualTo(LOCAL_VALUE);
    }

    @Test
    void a_path_outside_the_allowed_roots_is_refused() throws Exception {
        assertRefused(outsideProject(outside, "zz-g10-out").toString(), "zz-g10-out");
    }

    @Test
    void a_dot_dot_path_that_leaves_the_allowed_root_is_refused() throws Exception {
        Path sibling = outsideProject(scanRoot().resolveSibling("scan-dotdot"), "zz-g10-dotdot");

        assertRefused(
                scanRoot() + "/../" + scanRoot().getParent().relativize(sibling), "zz-g10-dotdot");
    }

    /** A root compared as a string prefix would let this one in. */
    @Test
    void a_sibling_whose_name_starts_with_the_root_is_refused() throws Exception {
        Path evil = outsideProject(Path.of(scanRoot() + "-evil"), "zz-g10-prefix");

        assertRefused(evil.toString(), "zz-g10-prefix");
    }

    @Test
    void a_symlink_in_the_root_pointing_outside_is_refused() throws Exception {
        Path target = outsideProject(outside, "zz-g10-symlink");
        Path link = Files.createSymbolicLink(scanRoot().resolve("zz-g10-symlink-link"), target);

        assertRefused(link.toString(), "zz-g10-symlink");
    }

    @Test
    void a_relative_missing_or_blank_path_is_refused() throws Exception {
        assertErrorWithHint(mcp.text("kb_scan", Map.of("path", "zz-g10-relative")));
        assertErrorWithHint(
                mcp.text("kb_scan", Map.of("path", scanRoot().resolve("zz-g10-none").toString())));
        assertErrorWithHint(mcp.text("kb_scan", Map.of("path", "  ")));
        assertErrorWithHint(mcp.text("kb_scan", Map.of()));
    }

    private void assertRefused(String path, String name) throws Exception {
        String answer = mcp.text("kb_scan", Map.of("path", path));

        assertErrorWithHint(answer);
        boolean written =
                store.read(
                        tx ->
                                tx.execute(
                                                "MATCH (e:Entity) WHERE e.id STARTS WITH $name"
                                                        + " RETURN e.id",
                                                Map.of("name", name))
                                        .hasNext());
        assertThat(written).as("a refused scan writes nothing").isFalse();
    }

    /** A project that would create {@code <name>-db} if it were read. */
    private static Path outsideProject(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        return ScanProjects.springProject(dir, name, PROD_VALUE, LOCAL_VALUE);
    }
}
