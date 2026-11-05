package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Invariant 10: Cypher text is never logged. The agent's query can hold anything, a secret value
 * included, so a query that times out, does not parse or is refused must leave its text in no log
 * and no file under the data directory, Neo4j's own logs (graph/logs) among them. Only the absence
 * is asserted; how the text stays out is not this test's concern.
 */
@ExtendWith(OutputCaptureExtension.class)
class CypherLogLeakTest extends RunningServerTestBase {

    private static final String SENTINEL = "sentinel-cypher-text-7e41a9";
    private static final String TIMES_OUT =
            "UNWIND range(1, 100000) AS a UNWIND range(1, 100000) AS b"
                    + " RETURN count(*) AS n, '"
                    + SENTINEL
                    + "' AS tag";
    private static final String DOES_NOT_PARSE = "MATCH (n RETURN '" + SENTINEL + "'";
    private static final String REFUSED = "CREATE (n:Entity {id: '" + SENTINEL + "'}) RETURN n.id";

    @Value("${agentkb.data-dir}")
    private Path dataDir;

    @Test
    void no_log_and_no_file_holds_the_text_of_a_failed_query(CapturedOutput output)
            throws Exception {
        McpClient mcp = McpClient.open(port);

        for (String query : List.of(TIMES_OUT, DOES_NOT_PARSE, REFUSED)) {
            assertErrorWithHint(mcp.text("kb_cypher", Map.of("query", query)));
        }

        assertThat(output.getAll()).as("application log").doesNotContain(SENTINEL);
        assertThat(filesHoldingSentinel()).as("files under %s", dataDir).isEmpty();
    }

    private List<Path> filesHoldingSentinel() throws IOException {
        byte[] sentinel = SENTINEL.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(dataDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> contains(read(file), sentinel))
                    .map(dataDir::relativize)
                    .toList();
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file, e);
        }
    }

    // ISO-8859-1 maps every byte to one char, so a byte search becomes a String search.
    private static boolean contains(byte[] content, byte[] sentinel) {
        return new String(content, StandardCharsets.ISO_8859_1)
                .contains(new String(sentinel, StandardCharsets.ISO_8859_1));
    }
}
