package io.github.ismetdyn.agentkb.server.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC §7 rule 7: user_stated needs its evidence in the user's messages of the last two hours
 * (PromptRing, RAM only); otherwise the source is agent. A wrong true lets an agent raise its own
 * guess above a scan, so the negatives matter most.
 */
class PromptRingTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final String PROMPT =
            "Sunucu İzmir'de duruyor, prod IP'si 198.51.100.7 ve erişim bastion üzerinden.";

    private final PromptRing ring = new PromptRing();

    @Test
    void words_from_a_recent_prompt_are_evidence() {
        ring.record(PROMPT, NOW.minus(Duration.ofMinutes(5)));

        assertThat(ring.contains("prod IP'si 198.51.100.7", NOW)).isTrue();
        assertThat(ring.contains("Sunucu İzmir'de duruyor", NOW)).isTrue();
    }

    @Test
    void words_the_user_never_wrote_are_not_evidence() {
        ring.record(PROMPT, NOW.minus(Duration.ofMinutes(5)));

        assertThat(ring.contains("prod IP'si 198.51.100.8", NOW)).isFalse();
        assertThat(ring.contains(PROMPT + " Ayrıca port 8443.", NOW)).isFalse();
    }

    /**
     * Review finding: a fragment of a longer token is no evidence. The user wrote 198.51.100.7 and
     * "üzerinden", never 98.51.100.7 or "bastion üzer".
     */
    @ParameterizedTest(name = "fragment {0}")
    @ValueSource(strings = {"198.51.100.", "98.51.100.7", "prod IP'si 198.51", "bastion üzer"})
    void a_fragment_of_a_longer_token_is_not_evidence(String evidence) {
        ring.record(PROMPT, NOW.minus(Duration.ofMinutes(5)));

        assertThat(ring.contains(evidence, NOW)).isFalse();
    }

    /**
     * Token edges: a sentence end, an apostrophe or a slash ends a token; '-', '_' and "x.y" do
     * not.
     */
    @ParameterizedTest(name = "{0} in {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "198.51.100.7 | ip 198.51.100.7. | true",
                "198.51.100.7 | (198.51.100.7) | true",
                "5432 | 5432/tcp | true",
                "543 | 5432/tcp | false",
                "db | db-prod | false",
                "db | prod_db | false",
                "ornek | ornek.example | false",
                "198.51.100.7 | 198.51.100.70 | false",
                "198.51.100.7 | 198.51.100.7 ve 198.51.100.70 | true",
                "198.51.100.7 | 198.51.100.70 ve 198.51.100.7 | true"
            })
    void a_match_must_start_and_end_on_a_token_edge(String words, String text, boolean expected) {
        assertThat(PromptRing.holds(text, words)).isEqualTo(expected);
    }

    @Test
    void an_empty_ring_holds_no_evidence() {
        assertThat(ring.contains("198.51.100.7", NOW)).isFalse();
    }

    @Test
    void a_prompt_older_than_two_hours_is_no_longer_evidence() {
        ring.record(PROMPT, NOW.minus(Duration.ofHours(2).plusMinutes(1)));

        assertThat(ring.contains("198.51.100.7", NOW)).isFalse();
    }

    @Test
    void a_prompt_just_inside_two_hours_is_still_evidence() {
        ring.record(PROMPT, NOW.minus(Duration.ofHours(2).minusMinutes(1)));

        assertThat(ring.contains("198.51.100.7", NOW)).isTrue();
    }

    /**
     * Retained memory is bounded: a prompt keeps its first 8,192 chars only, ending before the word
     * the limit cuts (a cut word would hold as a whole token: "bastionhost" cut to "ba").
     */
    @Test
    void only_the_head_of_a_long_prompt_up_to_its_last_whole_word_is_evidence() {
        String head = "a ".repeat(4_095);
        String tail = " " + "dolgu ".repeat(2_000) + "sonda 203.0.113.9";
        ring.record(head + "bastionhost" + tail, NOW.minus(Duration.ofMinutes(5)));

        assertThat(ring.contains("a a", NOW)).isTrue();
        assertThat(ring.contains("ba", NOW)).isFalse();
        assertThat(ring.contains("bastionhost", NOW)).isFalse();
        assertThat(ring.contains("sonda 203.0.113.9", NOW)).isFalse();

        PromptRing exact = new PromptRing();
        exact.record("a ".repeat(4_092) + "bastionx", NOW.minus(Duration.ofMinutes(5)));
        assertThat(exact.contains("bastionx", NOW)).as("8,192 chars fit whole").isTrue();
    }

    /** Old prompts leave the ring on the next record, not only when they are looked up. */
    @Test
    void a_prompt_older_than_two_hours_is_evicted_by_the_next_record() {
        Instant old = NOW.minus(Duration.ofHours(3));
        ring.record(PROMPT, old);
        ring.record("yeni mesaj", NOW);

        assertThat(ring.contains("198.51.100.7", old.plusSeconds(1))).isFalse();
    }

    /** An empty string is part of every prompt; it must not verify anything. */
    @ParameterizedTest(name = "blank evidence {0}")
    @ValueSource(strings = {"", " ", "\n\t "})
    void blank_evidence_is_never_evidence(String evidence) {
        ring.record(PROMPT, NOW.minus(Duration.ofMinutes(5)));

        assertThat(ring.contains(evidence, NOW)).isFalse();
    }
}
