package io.github.ismetdyn.agentkb.core.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.render.ResultList.Hit;
import io.github.ismetdyn.agentkb.core.render.ResultList.Step;
import io.github.ismetdyn.agentkb.core.render.ResultList.Tier;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Invariant 12 "sıralar ve işaretler" under the snippet cut (SPEC §5 "Metin sınırları"): an
 * imported value, summary or path longer than the cut loses free text, never its mark. Each row is
 * one hit whose free text is 600 characters, the marks its line must still show, and the start of
 * the free text that must stay before the ellipsis.
 */
class ResultListMarksTest {

    private static final int LONG_CHARS = 600;
    private static final int LIMIT = 10;
    private static final int BUDGET = 8_000;
    private static final String ELLIPSIS = "…";
    private static final Instant JAN_15 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant MAY_10 = Instant.parse("2026-05-10T09:00:00Z");
    private static final Instant OCT_28 = Instant.parse("2026-10-28T15:00:00Z");
    private static final String VALUE = repeatedToLong("uzundeger");
    private static final String SUMMARY = repeatedToLong("uzunozet");
    private static final int ID_CHARS = 120;

    static Stream<Arguments> longFreeText() {
        return Stream.of(
                argumentSet(
                        "closed fact keeps its old range and the current value",
                        factHit(fact(VALUE, FactStatus.OK, MAY_10), List.of("192.0.2.21")),
                        List.of("eski (2026-01-15 → 2026-05-10)", "güncel: 192.0.2.21"),
                        VALUE),
                argumentSet(
                        "conflict fact keeps ÇELİŞKİ and the kept values",
                        factHit(fact(VALUE, FactStatus.CONFLICT, null), List.of("192.0.2.21")),
                        List.of("ÇELİŞKİ (192.0.2.21 korunuyor)"),
                        VALUE),
                argumentSet(
                        "archived record keeps arşiv",
                        new Hit("ornek-app-00", "Server", Tier.HISTORY, true, SUMMARY, 1.0),
                        List.of("ornek-app-00 · Server · arşiv — "),
                        SUMMARY),
                argumentSet(
                        "long path keeps the archived step and the ended relation",
                        ResultList.path(
                                id('a'),
                                List.of(
                                        step(id('b'), false, null),
                                        step(id('c'), true, null),
                                        step(id('d'), false, MAY_10))),
                        List.of(id('c') + " (arşiv)", "eski (2026-01-15 → 2026-05-10)"),
                        id('a')));
    }

    @ParameterizedTest
    @MethodSource("longFreeText")
    void a_cut_line_keeps_every_mark(Hit hit, List<String> marks, String freeText) {
        String text = ResultList.render(List.of(hit), LIMIT, BUDGET);

        assertThat(text).as("one line, no more-line").startsWith("1. ").doesNotContain("(+");
        for (String mark : marks) {
            assertThat(text).as("mark '%s' in %s", mark, text).contains(mark);
        }
    }

    @ParameterizedTest
    @MethodSource("longFreeText")
    void a_cut_line_cuts_the_free_text_with_an_ellipsis(
            Hit hit, List<String> marks, String freeText) {
        String text = ResultList.render(List.of(hit), LIMIT, BUDGET);

        assertThat(text)
                .contains(freeText.substring(0, freeText.length() / 10))
                .contains(ELLIPSIS)
                .hasSizeLessThan(hit.snippet().length());
    }

    private static Hit factHit(Fact fact, List<String> kept) {
        Tier tier = fact.validTo() == null ? Tier.CURRENT : Tier.HISTORY;
        return new Hit(
                "ornek-app-01",
                "Server",
                tier,
                false,
                FactLine.snippet(new FactView(fact, null, null), kept),
                1.0);
    }

    private static Fact fact(String value, FactStatus status, @Nullable Instant validTo) {
        Instant observed = validTo == null ? OCT_28 : JAN_15;
        return new Fact(
                "ip-long",
                "ip",
                value,
                "prod",
                Source.AGENT,
                JAN_15,
                observed,
                validTo,
                status,
                null,
                null);
    }

    private static Step step(String other, boolean archived, @Nullable Instant validTo) {
        return new Step(
                new RelationView(
                        "DEPENDS_ON",
                        true,
                        other,
                        null,
                        Source.USER,
                        JAN_15,
                        validTo == null ? JAN_15 : validTo,
                        validTo),
                "Service",
                archived);
    }

    private static String id(char letter) {
        return String.valueOf(letter).repeat(ID_CHARS);
    }

    private static String repeatedToLong(String word) {
        StringBuilder text = new StringBuilder();
        while (text.length() < LONG_CHARS) {
            text.append(word).append(' ');
        }
        return text.substring(0, LONG_CHARS);
    }
}
