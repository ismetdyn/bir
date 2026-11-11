package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0026: a write is refused for a stored value by the rule reads mask by, in the text as written
 * and normalized. A letters-only value of 4 to 7 characters counts only as a whole token, so a
 * longer word that holds it is written; one with a digit or symbol counts anywhere.
 */
class StoredValueRefusalTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final String HOST = "zz-token-host";
    // Letters only, so 4 to 7 characters match only as a whole token.
    private static final String SHORT = "zqkxw";
    // F3: a digit makes a short value match anywhere.
    private static final String SHORT_DIGIT = "pa55w";
    // F2: refused in the normalized form of a text too.
    private static final String MIXED = "Kb7Qz-Prod-Pw";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void storeAShortValue() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(request(Source.USER, HOST, T1, fact("os", "rhel 9", PROD)));
        graph.writes.putSecret(HOST + "/prod/kbtest-short", SHORT);
        graph.writes.putSecret(HOST + "/prod/kbtest-digit", SHORT_DIGIT);
        graph.writes.putSecret(HOST + "/prod/kbtest-mixed", MIXED);
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    @Test
    void a_short_stored_value_inside_a_longer_word_is_written() {
        WriteResult result =
                graph.write(
                        request(Source.USER, HOST, T1, fact("note", "kb" + SHORT + "test", PROD)));

        assertThat(result.added()).hasSize(1);
    }

    @Test
    void a_short_stored_value_as_a_whole_word_is_refused() {
        assertThatThrownBy(
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                HOST,
                                                T1,
                                                fact("note", "pw " + SHORT + " here", PROD))))
                .isInstanceOf(WriteRejectedException.class)
                .hasMessageNotContaining(SHORT);
    }

    @Test
    void a_short_stored_value_with_a_digit_is_refused_inside_a_word() {
        assertThatThrownBy(
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                HOST,
                                                T1,
                                                fact("note", "kb" + SHORT_DIGIT + "box", PROD))))
                .isInstanceOf(WriteRejectedException.class);
    }

    @Test
    void a_stored_value_is_refused_in_the_normalized_form_of_an_entity_id() {
        assertThatThrownBy(
                        () ->
                                graph.write(
                                        request(
                                                Source.USER,
                                                "KB7QZ-PROD-PW-db",
                                                T1,
                                                fact("os", "rhel 9", PROD))))
                .isInstanceOf(WriteRejectedException.class);
    }
}
