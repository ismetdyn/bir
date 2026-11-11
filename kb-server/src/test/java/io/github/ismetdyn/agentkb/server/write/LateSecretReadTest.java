package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.DATABASE;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.T1;
import static io.github.ismetdyn.agentkb.server.write.EditFixture.WAITING_HOSTNAME;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.relation;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.MarkdownView;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries.Direction;
import io.github.ismetdyn.agentkb.server.graph.ListQueries;
import io.github.ismetdyn.agentkb.server.graph.ListQueries.ActivityFilter;
import io.github.ismetdyn.agentkb.server.graph.ReadRows;
import io.github.ismetdyn.agentkb.server.hooks.BriefService;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Node;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat.Snapshot;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Invariant 2 for a value written as plain text first and stored on a Secret later: a write refuses
 * a stored value only once it is stored, so the text is in the graph. Every read answer masks it,
 * in the form it was written and in its normalized form (an alias, an id); the snapshot keeps the
 * user's text as written (invariant 8, lossless). One graph, each read path a case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LateSecretReadTest {

    private static final String MARKER = "9a8b7c1d2e3f4051";
    // Mixed case, so an alias (kept normalized) holds a form the raw value does not match.
    private static final String LATE = "KbTest-Late-" + MARKER;
    private static final String NORMALIZED = "kbtest-late-" + MARKER;
    private static final String BOX = NORMALIZED + "-box";
    private static final String PROJECT = "zz-late-proj";
    // B: a project whose id and stack hold the value; the brief finds them by the raw forms.
    private static final String RAW_PROJECT = NORMALIZED + "-proj";
    private static final String STACK = "stack:" + NORMALIZED;
    private static final String STACK_RULE = "Kbtest late stack rule.";
    // D: two entities whose ids differ only inside a stored value.
    private static final List<String> TWINS =
            List.of("zz-dd-kbdedupaaaa1111", "zz-dd-kbdedupbbbb2222");
    private static final String TWIN_WORD = "kbdedupword";
    // G: kb_cypher's toUpper of the value, which the mask does not know.
    private static final String MARKER_UPPER = "9A8B7C1D2E3F4051";
    private static final String MASK = WriteService.MASK;

    @TempDir static Path dir;

    private EditFixture fixture;
    private KnowledgeQueries queries;
    private ListQueries lists;

    @BeforeAll
    void writeTheValuesThenStoreThem() throws IOException {
        fixture = EditFixture.open(dir);
        WriteHarness graph = fixture.graph;
        graph.write(request(Source.USER, HOST, T1, fact("note", "login " + LATE, PROD)));
        graph.write(request(Source.USER, BOX, T1, fact("os", "rhel 9", PROD)));
        graph.write(relation(Source.USER, HOST, T1, "depends_on", BOX));
        fixture.edits().editEntity(DATABASE, List.of(LATE), "db of " + LATE);
        graph.writes.propose(
                new ProposalRequest(Source.AGENT, "rule-change", null, "kbtest " + LATE, null));
        graph.write(
                new WriteRequest(
                        Source.USER,
                        PROJECT,
                        "project",
                        List.of(),
                        List.of(new RelationInput("depends_on", HOST, null)),
                        null,
                        null,
                        T1));
        fixture.edits().editEntity(PROJECT, null, "project of " + LATE);
        graph.writes
                .maintenance()
                .importRecords(
                        new Snapshot(
                                List.of(new Node(STACK, List.of("Stack"), Map.of())), List.of()));
        String decision =
                graph.writes.propose(
                        new ProposalRequest(
                                Source.AGENT,
                                "decision",
                                STACK,
                                STACK_RULE + "\n\nKbtest body.",
                                null));
        fixture.edits().acceptProposal(decision);
        graph.write(
                new WriteRequest(
                        Source.USER,
                        RAW_PROJECT,
                        "project",
                        List.of(fact("os", "rhel 9", PROD), fact("stack", LATE, PROD)),
                        List.of(),
                        null,
                        null,
                        T1));
        for (String twin : TWINS) {
            graph.write(request(Source.USER, twin, T1, fact("note", TWIN_WORD, PROD)));
        }
        // The conflict value waiting next to HOST's hostname becomes a secret too.
        graph.writes.putSecret(HOST + "/prod/kbtest-late", LATE);
        graph.writes.putSecret(HOST + "/prod/kbtest-old-host", WAITING_HOSTNAME);
        for (String twin : TWINS) {
            graph.writes.putSecret(HOST + "/prod/" + twin, twin.substring("zz-dd-".length()));
        }
        // C: a stored value that a ULID happens to hold.
        graph.writes.putSecret(HOST + "/prod/kbtest-ulid", fixture.waitingFact.substring(6, 20));
        queries = new KnowledgeQueries(graph.store);
        lists = new ListQueries(graph.store);
    }

    @AfterAll
    void closeGraph() {
        fixture.close();
    }

    private static Arguments read(String name, Function<LateSecretReadTest, Object> answer) {
        return arguments(name, answer);
    }

    static Stream<Arguments> reads() {
        return Stream.of(
                read("entity view", t -> MarkdownView.render(t.queries.entity(HOST).orElseThrow())),
                read("entity record", t -> t.queries.entity(HOST).orElseThrow()),
                read("entity by alias", t -> t.queries.entity(LATE).orElseThrow()),
                read("entity by normalized id", t -> t.queries.entity(BOX).orElseThrow()),
                read("search", t -> t.queries.search(MARKER, null, null)),
                read(
                        "stale",
                        t ->
                                t.queries.stale(
                                        List.of("Server", "Project"),
                                        Instant.now().plus(Duration.ofDays(1)))),
                read("related", t -> t.queries.related(HOST, null, Direction.BOTH, 2)),
                read("conflicts", t -> t.lists.conflicts()),
                read("proposals", t -> t.lists.proposals()),
                read("activity", t -> t.lists.activity(new ActivityFilter(null, null, null))),
                read(
                        "batch changes",
                        t ->
                                t.lists.activity(new ActivityFilter(null, null, null)).stream()
                                        .map(batch -> t.lists.changes(batch.batch()))
                                        .toList()),
                read("entity list", t -> t.lists.entities(null)),
                read(
                        "brief",
                        t ->
                                new BriefService(t.fixture.graph.store, t.queries)
                                        .forProject(PROJECT)
                                        .orElseThrow()),
                read("cypher", t -> t.queries.cypher("MATCH (e:Entity) RETURN e")),
                // A list property comes as a Java array, not a List, through a read row.
                read(
                        "raw array property",
                        t ->
                                t.fixture.graph.store.read(
                                        tx ->
                                                ReadRows.in(tx)
                                                        .rows(
                                                                "MATCH (e:Entity {id: $id})"
                                                                        + " RETURN e.aliases AS a",
                                                                Map.of("id", DATABASE),
                                                                row -> row.get("a")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reads")
    void a_value_stored_after_it_was_written_is_masked(
            String name, Function<LateSecretReadTest, Object> read) {
        String answer = String.valueOf(read.apply(this));

        assertThat(answer).as(name).contains(MASK);
        assertThat(answer).as(name).doesNotContain(MARKER).doesNotContain(WAITING_HOSTNAME);
    }

    @Test
    void the_brief_finds_the_project_and_its_stack_rule_by_their_raw_forms() {
        String brief =
                new BriefService(fixture.graph.store, queries)
                        .forProject(RAW_PROJECT)
                        .orElseThrow();

        assertThat(brief).contains(STACK_RULE, "os: rhel 9", MASK).doesNotContain(MARKER);
    }

    @Test
    void search_keeps_two_records_whose_ids_differ_only_inside_a_stored_value() {
        assertThat(queries.search(TWIN_WORD, null, null))
                .extracting(Hit::id)
                .containsExactly("zz-dd-" + MASK, "zz-dd-" + MASK);
    }

    @Test
    void a_server_written_id_that_holds_a_stored_value_stays_whole() {
        assertThat(String.valueOf(lists.conflicts())).contains(fixture.waitingFact);
        assertThat(String.valueOf(queries.entity(HOST).orElseThrow()))
                .contains(fixture.waitingFact);
    }

    /** ADR-0022 decision 24: a query that reshapes a value is outside what the mask can know. */
    @Test
    void cypher_that_reshapes_a_value_is_a_known_limit() {
        String upper =
                String.valueOf(
                        queries.cypher(
                                "MATCH (e:Entity {id: '"
                                        + DATABASE
                                        + "'}) RETURN toUpper(e.summary) AS s"));

        assertThat(upper).contains(MARKER_UPPER);
    }

    @Test
    void the_snapshot_keeps_the_text_as_written() {
        String snapshot = String.valueOf(new SnapshotExporter(fixture.graph.store).export());

        assertThat(snapshot).contains("login " + LATE, NORMALIZED, WAITING_HOSTNAME);
    }
}
