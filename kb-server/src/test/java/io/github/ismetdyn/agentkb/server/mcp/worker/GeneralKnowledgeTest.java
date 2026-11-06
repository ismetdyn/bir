package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0024, SPEC §6.1 and §7 rule 11: {@code topic} is an entity type with ids {@code topic:<name>}
 * that holds facts and relations; {@code note} is a multi-valued text key of at most 500
 * characters; a key outside schema/facts.yaml is rejected with a hint naming {@code note} and
 * {@code kb_propose(kind=schema)}, and such a proposal changes nothing until the user accepts it.
 */
class GeneralKnowledgeTest extends RunningServerTestBase {

    private static final String SECRET = "password=" + "Sup3rS3cretG12b";
    private static final String TOKEN = "ghp" + "_4k8Z2x6W5v1U3t9S7r0Q5p8O3n6M1l4K7j2H";
    private static final int NOTE_LIMIT = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EmbeddedNeo4jStore store;

    @Value("${agentkb.repo}")
    private Path repo;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws Exception {
        mcp = McpClient.open(port);
    }

    @Test
    void a_topic_record_holds_facts_and_relations() throws Exception {
        String topic = "topic:zz-g12b-yedekleme";
        remember("zz-g12b-topic-peer", Map.of("key", "os", "value", "rhel 9"));

        String reply =
                mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity", topic,
                                "type", "topic",
                                "facts",
                                        List.of(
                                                Map.of(
                                                        "key", "note",
                                                        "value", "Yedekler her gece alınır.")),
                                "relations",
                                        List.of(
                                                Map.of(
                                                        "type", "related",
                                                        "target", "zz-g12b-topic-peer")),
                                "source", Map.of("kind", "code")));

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertThat(rows("MATCH (t:Entity {id: $id}) RETURN labels(t) AS l", Map.of("id", topic)))
                .singleElement()
                .satisfies(row -> assertThat(row.get("l").toString()).contains("Entity", "Topic"));
        assertThat(mcp.text("kb_read", Map.of("id", topic)))
                .contains("note: Yedekler her gece alınır.")
                .contains("zz-g12b-topic-peer");
        assertThat(mcp.text("kb_read", Map.of("id", "topic:tls")))
                .as("the seed topic reads as a record (seed/make_seed.py)")
                .contains("TLS ve sertifika zinciri konusu.");
    }

    @Test
    void notes_are_multi_valued_so_a_second_note_keeps_the_first() throws Exception {
        String entity = "zz-g12b-notes";
        remember(entity, Map.of("key", "note", "value", "Bakım penceresi salı gecesidir."));
        remember(entity, Map.of("key", "note", "value", "Disk genişletmesi elle yapılır."));

        assertThat(
                        rows(
                                "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact {key: 'note'})"
                                        + " RETURN f.value AS v, f.valid_to AS to, f.status AS s",
                                Map.of("id", entity)))
                .hasSize(2)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("to")).as("both current").isNull();
                            assertThat(row.get("s")).isNotEqualTo("conflict");
                        });
    }

    @Test
    void a_note_of_500_characters_is_stored_and_one_of_501_is_rejected() throws Exception {
        String stored =
                remember(
                        "zz-g12b-note-500", Map.of("key", "note", "value", "ş".repeat(NOTE_LIMIT)));
        String rejected =
                remember(
                        "zz-g12b-note-501",
                        Map.of("key", "note", "value", "ş".repeat(NOTE_LIMIT + 1)));

        assertThat(stored).doesNotStartWith("{\"error\"");
        assertErrorWithHint(rejected);
        assertThat(JSON.readTree(rejected).path("hint").asString()).contains("kb doc add");
        assertThat(entityCount("zz-g12b-note-501")).isZero();
    }

    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {SECRET, TOKEN})
    void a_secret_looking_note_is_rejected_and_not_echoed(String value) throws Exception {
        String entity = "zz-g12b-note-secret-" + value.length();

        String reply = remember(entity, Map.of("key", "note", "value", "erişim: " + value));

        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString()).contains("kb secret set");
        assertThat(reply).doesNotContain(value);
        assertThat(entityCount(entity)).isZero();
    }

    @Test
    void an_unknown_key_is_rejected_with_a_hint_naming_note_and_a_schema_proposal()
            throws Exception {
        String reply = remember("zz-g12b-unknown-key", Map.of("key", "rack_unit", "value", "4U"));

        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString())
                .contains("note")
                .contains("kb_propose(kind=schema)");
        assertThat(entityCount("zz-g12b-unknown-key")).isZero();
    }

    /** SPEC §7 rule 6: a schema change is normative; the vocabulary waits for the user. */
    @Test
    void a_schema_proposal_opens_a_proposal_and_leaves_the_vocabulary_unchanged() throws Exception {
        Path facts = repo.resolve("schema").resolve("facts.yaml");
        String vocabulary = Files.readString(facts);
        String content = "Yeni anahtar zz_g12b_rack_unit: kind text, multi false.";

        String reply =
                mcp.text(
                        "kb_propose",
                        Map.of(
                                "kind", "schema",
                                "content", content,
                                "evidence", "Raf bilgisi üç sunucuda note olarak yazıldı."));

        assertThat(reply).doesNotStartWith("{\"error\"").contains("Proposal");
        assertThat(
                        rows(
                                "MATCH (p:Proposal {content: $c}) RETURN p.kind AS k, p.status AS s",
                                Map.of("c", content)))
                .singleElement()
                .isEqualTo(Map.of("k", "schema", "s", "open"));
        assertThat(Files.readString(facts)).isEqualTo(vocabulary);
        assertErrorWithHint(
                remember(
                        "zz-g12b-proposed-key", Map.of("key", "zz_g12b_rack_unit", "value", "4U")));
    }

    private String remember(String entity, Map<String, String> fact) throws Exception {
        return mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        entity,
                        "type",
                        "server",
                        "facts",
                        List.of(fact),
                        "source",
                        Map.of("kind", "code")));
    }

    private long entityCount(String id) {
        return (Long)
                rows("MATCH (e:Entity {id: $id}) RETURN count(e) AS n", Map.of("id", id))
                        .getFirst()
                        .get("n");
    }

    private List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(tx -> tx.execute(cypher, params).stream().toList());
    }
}
