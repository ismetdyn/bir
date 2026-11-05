package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.ExampleQueries;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * kb_schema (docs/MCP-TOOLS.md): labels, relation types with meanings, fact keys, decision fields
 * and the example queries, read from the repo's schema/*.yaml (a copy of seed/schema). Expected
 * values are copied from seed/schema by hand; the example queries come from the document.
 */
class SchemaToolTest extends RunningServerTestBase {

    private String schema;

    @BeforeEach
    void callTheTool() throws Exception {
        schema = McpClient.open(port).text("kb_schema", Map.of());
    }

    @Test
    void the_answer_is_data_and_fits_the_budget() {
        assertThat(schema.lines().findFirst()).contains(DATA_HEADER);
        assertThat(schema.length()).isLessThanOrEqualTo(Answers.MAX_CHARS);
    }

    @Test
    void every_entity_label_and_record_label_is_listed() {
        assertThat(schema)
                .contains(
                        List.of(
                                "Entity",
                                "Server",
                                "Cluster",
                                "Service",
                                "Database",
                                "Network",
                                "Environment",
                                "Project",
                                "Person",
                                "Team",
                                "Fact",
                                "Decision",
                                "Secret"));
    }

    /** A relation type and its meaning share a line, so the agent can pick the right one. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "RUNS               | Bu sunucu/küme şu servisi/uygulamayı çalıştırır",
                "DEPLOYED_ON        | Proje/servis şu sunucu, küme veya ortamda yayında",
                "DEPENDS_ON         | Çalışmak için şuna ihtiyaç duyar",
                "BEHIND             | Trafik şunun arkasından gelir",
                "ROUTES_TO          | Load balancer/proxy trafiği şuna yönlendirir",
                "PART_OF            | Daha büyük bir bütünün parçası",
                "AUTHENTICATES_WITH | Kimlik doğrulama için şunu kullanır",
                "OWNED_BY           | Sorumlu kişi/ekip",
                "RELATED            | Genel ilişki"
            })
    void every_relation_type_is_listed_with_its_meaning(String type, String meaning) {
        assertThat(schema.lines())
                .as(schema)
                .anySatisfy(line -> assertThat(line).contains(type).contains(meaning));
    }

    @Test
    void every_fact_key_is_listed() {
        assertThat(schema)
                .contains(
                        List.of(
                                "hostname",
                                "ip",
                                "port",
                                "url",
                                "jdbc_url",
                                "db_name",
                                "engine",
                                "os",
                                "version",
                                "stack",
                                "java_version",
                                "framework_version",
                                "namespace",
                                "image",
                                "owner",
                                "role",
                                "constraint",
                                "access",
                                "convention",
                                "build",
                                "finding"));
    }

    @Test
    void the_decision_fields_are_listed() {
        assertThat(schema).contains("proposed", "accepted", "superseded", "global");
    }

    @Test
    void every_example_query_of_the_docs_is_shown() {
        String shown = ExampleQueries.squeeze(schema);

        assertThat(ExampleQueries.fromDocs())
                .allSatisfy(query -> assertThat(shown).contains(ExampleQueries.squeeze(query)));
    }
}
