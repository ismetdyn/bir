package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.untyped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ARCHITECTURE §5 step 1 and SPEC §7 rules 8 and 10: a new write is checked before anything is
 * written, and a rejected request leaves the graph exactly as it was, even when its other parts are
 * valid (one transaction per write). Hints name the way out (docs/MCP-TOOLS.md); the rejected value
 * itself never appears in a message (invariant 2).
 */
class WriteRejectionTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String HOST = "zz-host";
    private static final String TARGET = "zz-db";
    private static final String SECRET = "password=" + "Sup3rS3cretZZ7";
    private static final int TEXT_FACT_LIMIT = 500;
    // A two-byte letter in UTF-8: the limit counts characters, not bytes.
    private static final String TURKISH_LETTER = "ş";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(
                request(Source.USER, HOST, T1, fact("hostname", "zz-host.ornek.example", PROD)));
        graph.write(request(Source.USER, TARGET, T1, fact("os", "rhel 9", PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    private static Stream<Arguments> rejected() {
        var valid = fact("os", "rhel 9", PROD);
        return Stream.of(
                arguments(
                        "unknown fact key",
                        request(Source.USER, HOST, T2, valid, fact("colour", "blue", PROD)),
                        "kb_schema"),
                arguments(
                        "secret-looking value",
                        request(Source.USER, HOST, T2, valid, fact("access", SECRET, PROD)),
                        "kb secret set"),
                arguments(
                        "text fact over 500 characters",
                        request(
                                Source.USER,
                                HOST,
                                T2,
                                valid,
                                fact("role", TURKISH_LETTER.repeat(TEXT_FACT_LIMIT + 1), PROD)),
                        "kb doc add"),
                arguments(
                        "unknown entity without a type",
                        untyped(Source.USER, "zz-missing", T2, valid),
                        "type"),
                arguments(
                        "unknown relation type",
                        relation(Source.USER, HOST, T2, "likes", TARGET),
                        "kb_schema"),
                arguments(
                        "relation to a missing entity",
                        relation(Source.USER, HOST, T2, "depends_on", "zz-nowhere"),
                        "kb_search"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void a_rejected_request_writes_nothing_and_names_the_way_out(
            String reason, WriteRequest request, String hintNames) {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(WriteRejectedException.class, () -> graph.write(request));

        assertThat(rejected).as(reason).isNotNull();
        assertThat(rejected.hint()).as(reason).contains(hintNames);
        assertThat(rejected.getMessage() + rejected.hint()).doesNotContain(SECRET);
        assertThat(graph.state()).as(reason).isEqualTo(before);
    }

    @Test
    void a_text_fact_of_exactly_500_characters_is_written() {
        String text = TURKISH_LETTER.repeat(TEXT_FACT_LIMIT);

        graph.write(request(Source.USER, HOST, T2, fact("role", text, PROD)));

        assertThat(graph.openFacts(HOST, "role", PROD))
                .singleElement()
                .satisfies(f -> assertThat(f.get("value")).isEqualTo(text));
    }

    /** Lead decision (todo G7): ids are unique across every label, which Neo4j cannot enforce. */
    @Test
    void an_entity_id_held_by_a_record_of_another_label_is_rejected() {
        graph.writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(
                                        new Node(
                                                "zz-taken",
                                                List.of("Lesson"),
                                                Map.of("title", "t"))),
                                List.of()));
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class,
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                "zz-taken",
                                                T2,
                                                fact("os", "rhel 9", PROD))));

        assertThat(rejected).isNotNull();
        assertThat(graph.state()).isEqualTo(before);
    }

    /** ARCHITECTURE §5 step 2: an alias, in any case, finds the entity; nothing new is created. */
    @Test
    void an_alias_in_another_case_resolves_to_the_existing_entity() {
        graph.writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(
                                        new Node(
                                                "zz-aliased",
                                                List.of("Entity", "Server"),
                                                Map.of(
                                                        "aliases",
                                                        List.of("zz-alias-one"),
                                                        "status",
                                                        "current"))),
                                List.of()));

        WriteResult result =
                graph.write(untyped(Source.USER, "ZZ-ALIAS-ONE", T2, fact("os", "rhel 9", PROD)));

        assertThat(result.entity()).isEqualTo("zz-aliased");
        assertThat(graph.openFacts("zz-aliased", "os", PROD)).hasSize(1);
        assertThat(graph.rows("MATCH (e:Entity) RETURN e.id AS id", Map.of()))
                .extracting(row -> row.get("id"))
                .containsExactlyInAnyOrder(HOST, TARGET, "zz-aliased");
    }
}
