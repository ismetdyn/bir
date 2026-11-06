package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.BatchRevertTest.HOST;
import static io.github.ismetdyn.agentkb.server.write.BatchRevertTest.T1;
import static io.github.ismetdyn.agentkb.server.write.BatchRevertTest.T2;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.inBatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * When {@code WriteService.revert(batch)} refuses (BatchRevertTest has the contract): a {@link
 * WriteRejectedException} with message and hint (REST and MCP turn it into {error, hint}), and
 * nothing changes, signals and every property included. No partial revert: a batch whose records a
 * later batch built on is refused whole, and the hint names the later batch, which goes first.
 */
class BatchRevertRefusalTest {

    private static final String UNDONE = "kbtest-batch-undone";
    private static final String LATER = "kbtest-batch-later";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(inBatch(Source.USER, HOST, T1, UNDONE, fact("os", "rhel 9", PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"kbtest-no-such-batch", " ", ""})
    void an_unknown_or_empty_batch_is_refused_and_nothing_changes(String batch) {
        Map<String, Object> before = graph.state();

        WriteRejectedException refused = refusedRevert(batch);

        assertThat(refused.getMessage()).isNotBlank();
        assertThat(refused.hint()).isNotBlank();
        assertThat(graph.state()).isEqualTo(before);
    }

    @Test
    void a_reverted_batch_is_gone_so_a_second_revert_is_refused() {
        graph.writes.revert(UNDONE);
        Map<String, Object> before = graph.state();

        WriteRejectedException refused = refusedRevert(UNDONE);

        assertThat(refused.hint()).isNotBlank();
        assertThat(graph.state()).isEqualTo(before);
    }

    /**
     * A later batch closed a fact this batch opened. Deleting it would cut the later SUPERSEDES
     * chain, so the revert is refused whole; after the later batch is reverted, it goes.
     */
    @Test
    void a_batch_superseded_later_is_refused_naming_the_later_batch() {
        graph.write(inBatch(Source.USER, HOST, T2, LATER, fact("os", "rhel 10", PROD)));
        Map<String, Object> before = graph.state();

        WriteRejectedException refused = refusedRevert(UNDONE);

        assertThat(refused.hint()).contains(LATER);
        assertThat(graph.state()).isEqualTo(before);
        assertThat(graph.revertMarkers(UNDONE)).as("a refused revert leaves no marker").isEmpty();

        graph.writes.revert(LATER);
        graph.writes.revert(UNDONE);
        assertThat(graph.stateWithoutRevertMarkers()).as("both reverted in order").isEmpty();
        assertThat(graph.revertMarkers(UNDONE)).as("decision 42").containsExactly(UNDONE);
    }

    /**
     * A later batch wrote a fact onto the entity this batch created. Deleting the entity would
     * leave the later fact without its entity, so this is refused the same way.
     */
    @Test
    void a_batch_whose_entity_a_later_batch_wrote_onto_is_refused_naming_it() {
        graph.write(inBatch(Source.USER, HOST, T2, LATER, fact("version", "2.4", PROD)));
        Map<String, Object> before = graph.state();

        WriteRejectedException refused = refusedRevert(UNDONE);

        assertThat(refused.hint()).contains(LATER);
        assertThat(graph.state()).isEqualTo(before);
    }

    /**
     * Lead decision 4: a later batch disputed a fact of this batch with a lower source, so its
     * value waits as a conflict next to it (SPEC §7 rule 3). No edge of the later batch touches a
     * record of this batch (the entity is older), yet deleting the disputed fact would leave the
     * conflict disputing nothing; the revert is refused whole, naming the later batch.
     */
    @Test
    void a_batch_whose_fact_a_later_batch_disputed_as_a_conflict_is_refused_naming_it() {
        String entity = "zz-disputed-host";
        String older = "kbtest-batch-older";
        String disputed = "kbtest-batch-disputed";
        graph.write(inBatch(Source.USER, entity, T1, older, fact("version", "2.4", PROD)));
        graph.write(inBatch(Source.USER, entity, T1, disputed, fact("os", "rhel 9", PROD)));
        graph.write(inBatch(Source.AGENT, entity, T2, LATER, fact("os", "rhel 10", PROD)));
        assertThat(graph.openFacts(entity, "os", PROD))
                .as("precondition: the later value waits as a conflict next to the disputed one")
                .extracting(f -> f.get("status"))
                .containsExactlyInAnyOrder("ok", "conflict");
        Map<String, Object> before = graph.state();

        WriteRejectedException refused = refusedRevert(disputed);

        assertThat(refused.hint()).contains(LATER);
        assertThat(graph.state()).isEqualTo(before);

        graph.writes.revert(LATER);
        graph.writes.revert(disputed);
        assertThat(graph.openFacts(entity, "os", PROD)).as("both reverted in order").isEmpty();
    }

    private WriteRejectedException refusedRevert(String batch) {
        WriteRejectedException refused =
                catchThrowableOfType(
                        WriteRejectedException.class, () -> graph.writes.revert(batch));
        assertThat(refused).as("revert of %s was not refused", batch).isNotNull();
        return refused;
    }
}
