package io.github.ismetdyn.agentkb.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-11-05T10:00:00Z",
                "2026-11-05T10:00:00.5Z",
                "2026-11-05T10:00:00.999999999Z"
            })
    void every_precision_is_written_as_whole_seconds_in_utc(String given) {
        assertThat(Times.format(Instant.parse(given))).isEqualTo("2026-11-05T10:00:00Z");
    }

    @Test
    void the_written_form_has_a_fixed_length_like_the_seed() {
        assertThat(Times.format(Instant.parse("2026-01-01T00:00:00Z"))).hasSize(20);
        assertThat(
                        Times.format(
                                OffsetDateTime.of(2026, 1, 1, 3, 0, 0, 0, ZoneOffset.ofHours(3))
                                        .toInstant()))
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    /** SPEC §10: the newer updated_at wins, and a text comparison must agree with time order. */
    @Test
    void text_order_of_written_times_is_time_order() {
        String earlier = Times.format(Instant.parse("2026-11-05T10:00:00Z"));
        String later = Times.format(Instant.parse("2026-11-05T10:00:01.5Z"));

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void the_written_form_reads_back_with_instant_parse() {
        Instant time = Instant.parse("2026-05-10T09:00:00Z");

        assertThat(Instant.parse(Times.format(time))).isEqualTo(time);
    }
}
