package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * kb_remember with {@code source.document} (docs/MCP-TOOLS.md kb_remember, plan.md H7): every fact
 * the write stores gets a FROM edge to the Document, in the write's batch, so kb revert takes it
 * back. An id that names no Document is {@code {error, hint}} and nothing is stored.
 */
class RememberDocumentTest extends RunningServerTestBase {

    private static final String SEED_DOC = DocumentReadToolTest.SEED_DOC;
    private static final String FROM_DOCUMENT =
            """
            MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact)-[r:FROM]->(d:Document)
            RETURN d.id AS document, f.value AS value, r.batch AS edgeBatch, f.batch AS factBatch
            """;

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private WriteService writes;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    @Test
    void the_new_fact_gets_a_from_edge_to_the_document() throws Exception {
        String entity = "zz-h7-doc-" + UUID.randomUUID();

        String reply = remember(entity, SEED_DOC);

        assertThat(reply).as(reply).startsWith("Stored on");
        assertThat(rows(FROM_DOCUMENT, entity))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("document")).isEqualTo(SEED_DOC);
                            assertThat(row.get("value")).isEqualTo("restart with systemctl");
                            assertThat(row.get("edgeBatch")).isEqualTo(row.get("factBatch"));
                        });
        assertThat(mcp.text("kb_read", Map.of("id", SEED_DOC))).contains(entity);
    }

    @Test
    void reverting_the_write_takes_the_from_edge_back() throws Exception {
        String entity = "zz-h7-doc-revert-" + UUID.randomUUID();
        remember(entity, SEED_DOC);
        String batch = (String) rows(FROM_DOCUMENT, entity).getFirst().get("edgeBatch");

        writes.maintenance().revert(batch);

        assertThat(rows(FROM_DOCUMENT, entity)).isEmpty();
        assertThat(rows("MATCH (d:Document {id: $id}) RETURN d.id AS document", SEED_DOC))
                .as("the document stays")
                .hasSize(1);
    }

    /**
     * CP4 J2: a fact the user already holds keeps its own provenance (SPEC §7 rule 1), so it gets
     * no FROM edge; the reply says so instead of staying silent.
     */
    @Test
    void a_fact_a_higher_source_holds_gets_no_from_edge_and_the_reply_says_so() throws Exception {
        String entity = "zz-h7-doc-user-" + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        entity,
                        "service",
                        List.of(new FactInput("note", "restart with systemctl", null, null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));

        String reply = remember(entity, SEED_DOC);

        assertThat(reply)
                .as(reply)
                .startsWith("Stored on")
                .contains("FROM not added for 1 fact(s): a higher-trust source holds them");
        assertThat(rows(FROM_DOCUMENT, entity)).isEmpty();
    }

    /** An unknown id, a malformed one and the id of a record that is no Document. */
    @ParameterizedTest
    @ValueSource(strings = {"doc:000000000000", "doc:'; MATCH (n) DETACH DELETE n", "ornek-proje"})
    void an_id_that_names_no_document_is_refused_and_nothing_is_stored(String document)
            throws Exception {
        String entity = "zz-h7-doc-refused-" + UUID.randomUUID();

        String reply = remember(entity, document);

        assertErrorWithHint(reply);
        assertThat(rows("MATCH (e:Entity {id: $id}) RETURN e.id AS id", entity))
                .as("nothing stored")
                .isEmpty();
    }

    private String remember(String entity, String document)
            throws IOException, InterruptedException {
        return mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        entity,
                        "type",
                        "service",
                        "facts",
                        List.of(Map.of("key", "note", "value", "restart with systemctl")),
                        "source",
                        Map.of("kind", "doc", "ref", "runbook", "document", document)));
    }

    private List<Map<String, Object>> rows(String cypher, String id) {
        return store.read(tx -> tx.execute(cypher, Map.of("id", id)).stream().toList());
    }
}
