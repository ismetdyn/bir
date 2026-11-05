package io.github.ismetdyn.agentkb.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CLAUDE.md: String.toLowerCase() depends on the locale (Turkish I to dotless i, Locale.ROOT dotted
 * capital I to i plus a combining dot), so identity and alias normalization lives only in
 * Ids.normalize. Each row runs under every default locale below: Turkish and Azeri change I,
 * Lithuanian changes accented I.
 */
class IdsTest {

    private static final List<Locale> DEFAULT_LOCALES =
            List.of(
                    Locale.forLanguageTag("tr-TR"),
                    Locale.forLanguageTag("az-AZ"),
                    Locale.forLanguageTag("lt-LT"),
                    Locale.ROOT,
                    Locale.ENGLISH);
    private static final String COMBINING_DOT = String.valueOf((char) 0x0307);

    private static final List<String[]> NORMALIZED =
            List.of(
                    // The four forms of one Turkish word collapse: a typed id must not fork an
                    // entity by its case.
                    row("izmir", "izmir"),
                    row("İzmir", "izmir"),
                    row("Izmir", "izmir"),
                    row("IZMIR", "izmir"),
                    row("İZMİR", "izmir"),
                    row("ızmır", "izmir"),
                    // The upper case of a dotless word is written with I, so both have to meet too.
                    row("ısı", "isi"),
                    row("ISI", "isi"),
                    row("İSİ", "isi"),
                    row("isi", "isi"),
                    // Dotted capital I pasted in decomposed form.
                    row("I" + COMBINING_DOT + "zmir", "izmir"),
                    // The other Turkish letters only lose their case: they are not folded to ASCII.
                    row("ŞİŞLİ-01", "şişli-01"),
                    row("ĞÜÖÇ", "ğüöç"),
                    row("DİYARBAKIR", "diyarbakir"),
                    // Lithuanian lower-casing adds a combining dot to this one.
                    row("Ì", "ì"),
                    // Ids and aliases from the seed.
                    row("ornek-app-01", "ornek-app-01"),
                    row("ORNEK-APP-01", "ornek-app-01"),
                    row("App00", "app00"),
                    row("APP00", "app00"),
                    row("", ""));

    private Locale originalDefault;

    @BeforeEach
    void rememberDefaultLocale() {
        originalDefault = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("rowsUnderEveryDefaultLocale")
    void normalizes_the_same_under_any_default_locale(
            Locale defaultLocale, String input, String expected) {
        Locale.setDefault(defaultLocale);

        assertThat(Ids.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("rowsUnderEveryDefaultLocale")
    void normalizing_twice_changes_nothing(Locale defaultLocale, String input, String expected) {
        Locale.setDefault(defaultLocale);

        assertThat(Ids.normalize(Ids.normalize(input))).isEqualTo(expected);
    }

    @Test
    void different_ids_stay_different() {
        assertThat(Ids.normalize("ornek-app-01")).isNotEqualTo(Ids.normalize("ornek-app-02"));
        assertThat(Ids.normalize("şişli")).isNotEqualTo(Ids.normalize("sisli"));
        assertThat(Ids.normalize("app_00")).isNotEqualTo(Ids.normalize("app-00"));
    }

    private static Stream<Arguments> rowsUnderEveryDefaultLocale() {
        return DEFAULT_LOCALES.stream()
                .flatMap(
                        locale ->
                                NORMALIZED.stream().map(row -> arguments(locale, row[0], row[1])));
    }

    private static String[] row(String input, String expected) {
        return new String[] {input, expected};
    }
}
