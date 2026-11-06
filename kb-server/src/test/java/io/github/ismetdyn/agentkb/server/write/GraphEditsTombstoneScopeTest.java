package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.EditFixture.HOST;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lead decisions CP3 S5 and J5 on the tombstone of ADR-0025 decision 16 through the write path: a
 * closed hostname matches in any case (RFC 4343) while other keys compare exactly, and a close no
 * longer counts once the user opened the value again. The fixture's os fact (user, rhel 9) and
 * hostname fact (user_stated, h1.example) are the closed values.
 */
class GraphEditsTombstoneScopeTest {

    private static final String OS = "os";
    private static final String CLOSED_VALUE = "rhel 9";
    private static final String HOSTNAME = "hostname";

    @TempDir Path dir;

    private EditFixture fixture;

    @BeforeEach
    void openGraph() throws IOException {
        fixture = EditFixture.open(dir);
    }

    @AfterEach
    void closeGraph() {
        fixture.close();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private WriteResult write(Source source, String value) {
        return fixture.graph.write(request(source, HOST, now(), fact(OS, value, PROD)));
    }

    /** Lead decision (CP3 S5): a closed hostname is the same name in any case (RFC 4343). */
    @Test
    void a_lower_source_writing_a_closed_hostname_in_another_case_waits_as_a_conflict() {
        fixture.edits().closeFact(fixture.currentHostnameFact);

        WriteResult result =
                fixture.graph.write(
                        request(Source.AGENT, HOST, now(), fact(HOSTNAME, "H1.Example", PROD)));

        assertThat(result.conflicts()).hasSize(1);
        assertThat(fixture.graph.openFacts(HOST, HOSTNAME, PROD))
                .allSatisfy(open -> assertThat(open).containsEntry("status", "conflict"));
    }

    /** Only the tombstone of a hostname ignores case; os has no validator. */
    @Test
    void a_closed_value_of_a_key_without_the_hostname_validator_compares_exactly() {
        fixture.edits().closeFact(fixture.osFact);

        WriteResult result = write(Source.AGENT, "RHEL 9");

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.added()).hasSize(1);
    }

    /**
     * CP3 J5 as the lead put it: once the user wrote the closed value again, the old close does not
     * count. A scan cannot replace the user's value, so the agent only confirms it.
     */
    @Test
    void the_value_the_user_wrote_again_is_confirmed_not_disputed() {
        fixture.edits().closeFact(fixture.osFact);
        String reopened = write(Source.USER, CLOSED_VALUE).added().getFirst();
        assertThat(write(Source.SCAN, "rhel 10").conflicts()).as("scan waits").hasSize(1);

        WriteResult result = write(Source.AGENT, CLOSED_VALUE);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.refreshed()).containsExactly(reopened);
    }

    /**
     * CP3 J5, the reachable form: the user opened the value again and replaced it by a write of
     * their own, then closed that one. The first close no longer counts (lead decision), so a scan
     * of the value makes it current.
     */
    @Test
    void a_close_the_user_overrode_by_writing_the_value_again_is_no_tombstone() {
        fixture.edits().closeFact(fixture.osFact);
        write(Source.USER, CLOSED_VALUE);
        String replacement = write(Source.USER, "rhel 10").added().getFirst();
        fixture.edits().closeFact(replacement);

        WriteResult result = write(Source.SCAN, CLOSED_VALUE);

        assertThat(result.conflicts()).isEmpty();
        assertThat(fixture.graph.openFacts(HOST, OS, PROD))
                .singleElement()
                .satisfies(
                        open ->
                                assertThat(open)
                                        .containsEntry("value", CLOSED_VALUE)
                                        .containsEntry("source", "scan")
                                        .containsEntry("status", "ok"));
    }
}
