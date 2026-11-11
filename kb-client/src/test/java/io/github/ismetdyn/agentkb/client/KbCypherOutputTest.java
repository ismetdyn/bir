package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H6 review (CWE-74): kb cypher prints tab-separated values, one row per line, so a cell cannot add
 * a column or a row. A backslash is written {@code \\}, a tab {@code \t} and a line break {@code
 * \n}. The truncated notice goes to stderr: stdout holds the table only.
 */
class KbCypherOutputTest {

    private static final String CYPHER = "/api/v1/cypher";

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
    void a_cell_with_a_tab_line_break_or_backslash_stays_one_cell() {
        // JSON: column "a<TAB>b"; cells "x\y" and "row1<LF>forged<TAB>row".
        server.answer(
                "POST",
                CYPHER,
                200,
                "{\"columns\":[\"a\\tb\",\"c\"],"
                        + "\"rows\":[{\"cells\":[\"x\\\\y\",\"row1\\nforged\\trow\"]}],"
                        + "\"truncated\":false}");

        Result result = server.kb(workDir, "cypher", "MATCH (n) RETURN n");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("a\\tb\tc\nx\\\\y\trow1\\nforged\\trow\n");
    }

    @Test
    void the_truncated_notice_goes_to_stderr() {
        server.answer(
                "POST",
                CYPHER,
                200,
                "{\"columns\":[\"id\"],\"rows\":[{\"cells\":[\"kbtest-a\"]}],\"truncated\":true}");

        Result result = server.kb(workDir, "cypher", "MATCH (n) RETURN n.id AS id");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEqualTo("id\nkbtest-a\n");
        assertThat(result.stderr()).contains("truncated");
    }
}
