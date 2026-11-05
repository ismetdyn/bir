package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultLines;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * kb_stale (docs/MCP-TOOLS.md): current facts whose {@code observed_at} is older than {@code days}
 * (default 90), or that are {@code conflict}; only facts of operational entity types
 * (seed/schema/types.yaml). Seed dates (seed/README.md "Bayat"): ornek-app-01 facts 2026-05-10,
 * ornek-lb 2026-10-01, ornek-db 2026-10-29, ornek-proje (a project, not operational) 2026-10-01.
 * The tests use the real clock, so they only rely on facts getting older.
 */
class StaleToolTest extends RunningServerTestBase {

    // Older than any record can be: only status, not age, can list a fact.
    private static final int EVER = 100_000;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void the_default_of_90_days_lists_the_old_ornek_app_01_facts() throws Exception {
        String text = mcp.text("kb_stale", Map.of());

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        assertLine(text, "ornek-app-01", "hostname", "ornek-app-01.ornek.example", "2026-05-10");
        assertLine(text, "ornek-app-01", "os", "Rocky Linux 9", "2026-05-10");
        assertThat(text).isEqualTo(mcp.text("kb_stale", Map.of("days", 90)));
    }

    @Test
    void a_conflict_is_listed_whatever_its_age() throws Exception {
        String text = mcp.text("kb_stale", Map.of("days", EVER));

        assertLine(text, "ornek-app-01", "ip", "192.0.2.25", "çelişki");
    }

    @Test
    void a_verified_fact_younger_than_days_is_not_listed() throws Exception {
        String text = mcp.text("kb_stale", Map.of("days", EVER));

        assertNoLine(text, "ornek-app-01.ornek.example");
        assertNoLine(text, "Rocky Linux 9");
    }

    /** types.yaml "operational: true → kb_stale bu türlerin fact'lerine bakar". */
    @Test
    void only_facts_of_operational_types_are_listed() throws Exception {
        String text = mcp.text("kb_stale", Map.of("days", 1));

        assertLine(text, "ornek-db", "db_name", "ORNEKPDB");
        assertLine(text, "ornek-lb", "ip", "192.0.2.5");
        assertNoLine(text, "./mvnw verify");
        assertNoLine(text, "https://ornek.example");
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(ints = {-1, -90})
    void a_negative_days_answers_error_with_hint(int days) throws Exception {
        assertErrorWithHint(mcp.text("kb_stale", Map.of("days", days)));
    }

    private static void assertLine(String text, String... parts) {
        List<String> wanted = Stream.of(parts).map(Ids::normalize).toList();
        assertThat(resultLines(text))
                .as(text)
                .anySatisfy(line -> assertThat(Ids.normalize(line)).contains(wanted));
    }

    private static void assertNoLine(String text, String part) {
        assertThat(resultLines(text)).as(text).noneSatisfy(line -> assertThat(line).contains(part));
    }
}
