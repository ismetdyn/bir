package io.github.ismetdyn.agentkb.server.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one masking of stored values (invariant 2, ADR-0026): a value of 8 characters or more is
 * masked wherever it is; one of 4 to 7 only as a whole token, so a stored {@code pgadmin} masks
 * {@code pgadmin-db} but not {@code pgadmin4}; a shorter one never. Each covered run becomes one
 * mask.
 */
class SecretMaskTest {

    private static final String MASK = SecretMask.MASK;

    @Test
    void overlapping_values_become_one_mask() {
        SecretMask mask = SecretMask.of(List.of("kbtestHEADxyz7", "xyz7TAILkbtest"));

        assertThat(mask.text("a kbtestHEADxyz7TAILkbtest b")).isEqualTo("a " + MASK + " b");
    }

    @Test
    void a_value_inside_a_longer_one_leaves_nothing_of_either() {
        SecretMask mask = SecretMask.of(List.of("inner-kbtest", "outer-inner-kbtest-end"));

        assertThat(mask.text("x outer-inner-kbtest-end y")).isEqualTo("x " + MASK + " y");
    }

    @Test
    void adjacent_values_become_one_mask() {
        SecretMask mask = SecretMask.of(List.of("kbtestAAAA", "kbtestBBBB"));

        assertThat(mask.text("kbtestAAAAkbtestBBBB!")).isEqualTo(MASK + "!");
    }

    @Test
    void a_long_value_is_masked_inside_a_word() {
        SecretMask mask = SecretMask.of(List.of("Kbtest-Long-1"));

        assertThat(mask.text("xKbtest-Long-1y")).isEqualTo("x" + MASK + "y");
    }

    @Test
    void the_normalized_form_is_masked_when_read_from_the_graph_values() {
        SecretMask mask = SecretMask.of(List.of("KbTest-Late-1", "kbtest-late-1"));

        assertThat(mask.text("alias kbtest-late-1, value KbTest-Late-1"))
                .isEqualTo("alias " + MASK + ", value " + MASK);
    }

    @Test
    void a_short_value_is_masked_only_as_a_whole_token() {
        SecretMask mask = SecretMask.of(List.of("pgadmin"));

        assertThat(mask.text("pgadmin-db and pgadmin4,pgadmin"))
                .isEqualTo(MASK + "-db and pgadmin4," + MASK);
    }

    /** F3: a short value with a digit or symbol is masked anywhere, glued copies too. */
    @Test
    void a_short_value_with_a_digit_is_masked_anywhere() {
        SecretMask mask = SecretMask.of(List.of("pa55w"));

        assertThat(mask.text("?password%3Dpa55w")).isEqualTo("?password%3D" + MASK);
        assertThat(mask.holds("xpa55wy")).isTrue();
    }

    /** A value under four characters is no secret (ADR-0026): "do not" stays as it is. */
    @Test
    void a_value_under_four_characters_never_matches() {
        SecretMask mask = SecretMask.of(List.of("not"));

        assertThat(mask.text("Kbtest: do not touch the notes"))
                .isEqualTo("Kbtest: do not touch the notes");
        assertThat(mask.holds("not")).isFalse();
    }

    @Test
    void no_or_empty_values_mask_nothing() {
        assertThat(SecretMask.of(List.of()).text("kbtest text")).isEqualTo("kbtest text");
        assertThat(SecretMask.of(List.of("")).text("kbtest text")).isEqualTo("kbtest text");
    }

    @Test
    void server_written_columns_stay_and_texts_in_nested_values_are_masked() {
        String ulid = "01KBTESTFAKEULID0000000000";
        SecretMask mask = SecretMask.of(List.of("FAKEULID"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ulid);
        row.put("batch", ulid);
        row.put("at", "2026-11-11T00:00:00Z");
        row.put("entity", "zz-FAKEULID-box");
        row.put("fact", Map.of("id", ulid, "value", "x FAKEULID"));
        row.put("aliases", List.of("FAKEULID"));
        row.put("raw", new String[] {"zz-FAKEULID"});

        Map<String, Object> masked = mask.row(row);

        assertThat(masked.get("id")).isEqualTo(ulid);
        assertThat(masked.get("batch")).isEqualTo(ulid);
        assertThat(masked.get("entity")).isEqualTo("zz-" + MASK + "-box");
        assertThat(masked.get("fact")).isEqualTo(Map.of("id", ulid, "value", "x " + MASK));
        assertThat(masked.get("aliases")).isEqualTo(List.of(MASK));
        assertThat(masked.get("raw")).isEqualTo(List.of("zz-" + MASK));
    }
}
