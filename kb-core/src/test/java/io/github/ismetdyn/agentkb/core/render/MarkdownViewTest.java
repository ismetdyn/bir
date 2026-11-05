package io.github.ismetdyn.agentkb.core.render;

import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.hostnameClosedAfterBeingSeenAgain;
import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.ornekApp00;
import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.ornekApp01;
import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.ornekDb;
import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.ornekLb;
import static io.github.ismetdyn.agentkb.core.render.ViewFixtures.ornekProje;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ismetdyn.agentkb.core.Ids;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SPEC §6.6 record view over the seed's deliberate cases (seed/README.md). Exact lines are the SPEC
 * example verbatim; where the SPEC fixes no exact form a row names the parts one line must hold.
 * {@code HEAD} is the part between the heading and the first section.
 */
class MarkdownViewTest {

    private static final String HEAD = "";
    private static final String FACTS = "Bilgiler";
    private static final String RELATIONS = "İlişkiler";
    private static final String SECRETS = "Secret'lar";
    private static final String HISTORY = "Geçmiş";

    static Stream<Arguments> headings() {
        return Stream.of(
                argumentSet(
                        "current server",
                        ornekApp01(),
                        "# ornek-app-01 · Server · current",
                        "Örnek uygulama sunucusu."),
                argumentSet(
                        "archived entity is flagged",
                        ornekApp00(),
                        "# ornek-app-00 · Server · archived",
                        "Eski uygulama sunucusu; uygulamalar 2026-05'te ornek-app-01'e taşındı."),
                argumentSet(
                        "observed entity from the scanner",
                        ornekDb(),
                        "# ornek-db · Database · observed",
                        "Tarayıcının oluşturduğu örnek kayıt; kullanıcı doğrulamadı."),
                argumentSet(
                        "network entity",
                        ornekLb(),
                        "# ornek-lb · Network · current",
                        "Sunucuların önündeki load balancer + WAF (örnek)."));
    }

    static Stream<Arguments> exactLines() {
        return Stream.of(
                argumentSet(
                        "a current fact is dated by observed_at",
                        ornekApp01(),
                        FACTS,
                        "- hostname: ornek-app-01.ornek.example @prod · user, 2026-05-10"),
                argumentSet(
                        "the user value stays current next to a conflict",
                        ornekApp01(),
                        FACTS,
                        "- ip: 192.0.2.21 @prod · user, 2026-05-10"),
                argumentSet(
                        "a conflict is shown as ÇELİŞKİ naming the kept value",
                        ornekApp01(),
                        FACTS,
                        "- ip: 192.0.2.25 @prod · agent, 2026-10-28 · ÇELİŞKİ (192.0.2.21 korunuyor)"),
                argumentSet(
                        "a port points to its target and names its source file",
                        ornekApp01(),
                        FACTS,
                        "- port: 8443/https → ornek-proje @prod · scan, 2026-10-29"
                                + " · ornek-proje:src/main/resources/application-prod.yml:2"),
                argumentSet(
                        "current relations share one line in the given order",
                        ornekApp01(),
                        RELATIONS,
                        "- RUNS → ornek-proje · BEHIND → ornek-lb · PART_OF → ornek-prod-ortami"),
                argumentSet(
                        "a closed fact is history with its range",
                        ornekApp01(),
                        HISTORY,
                        "- ip: 192.0.2.20 @prod · user, 2026-01-15 → 2026-05-10"),
                argumentSet(
                        "the history range starts at valid_from, not at observed_at",
                        hostnameClosedAfterBeingSeenAgain(),
                        HISTORY,
                        "- hostname: ornek-app-01.ornek.example @prod · user, 2026-01-15 → 2026-06-01"));
    }

    static Stream<Arguments> lineParts() {
        return Stream.of(
                argumentSet("an alias is shown", ornekLb(), HEAD, List.of("ornek-f5")),
                argumentSet(
                        "an archived entity keeps its alias", ornekApp00(), HEAD, List.of("app00")),
                argumentSet(
                        "an ended relation is history with its range",
                        ornekProje(),
                        HISTORY,
                        List.of("DEPLOYED_ON", "ornek-app-00", "2026-01-15 → 2026-05-10")),
                argumentSet(
                        "an ended incoming relation is history too",
                        ornekApp00(),
                        HISTORY,
                        List.of("DEPLOYED_ON", "ornek-proje", "2026-01-15 → 2026-05-10")),
                argumentSet(
                        "an incoming relation is shown",
                        ornekProje(),
                        RELATIONS,
                        List.of("RUNS", "ornek-app-01")),
                argumentSet(
                        "an unverified fact from a document is marked and names the document",
                        ornekProje(),
                        FACTS,
                        List.of(
                                "convention: Yeniden başlatma: sudo systemctl restart ornek-proje;"
                                        + " loglar: journalctl -u ornek-proje",
                                "agent, 2026-10-30",
                                "doc:ded48257b6f3",
                                "doğrulanmamış")),
                argumentSet(
                        "a secret shows its name, env and placeholder",
                        ornekApp01(),
                        SECRETS,
                        List.of(
                                "deploy-ssh-key @prod",
                                "kb:secret/ornek-app-01/prod/deploy-ssh-key")));
    }

    static Stream<Arguments> absentText() {
        return Stream.of(
                argumentSet("a closed fact is not current", ornekApp01(), FACTS, "192.0.2.20"),
                argumentSet(
                        "a conflict is current, not history", ornekApp01(), HISTORY, "192.0.2.25"),
                argumentSet(
                        "an ended relation is not current",
                        ornekProje(),
                        RELATIONS,
                        "ornek-app-00"),
                argumentSet(
                        "a current relation is not history",
                        ornekProje(),
                        HISTORY,
                        "ornek-app-01"));
    }

    @ParameterizedTest
    @MethodSource("headings")
    void the_heading_names_id_type_and_status_and_the_summary_follows(
            EntityView view, String heading, String summary) {
        List<String> lines = MarkdownView.render(view).lines().toList();
        int at = lines.indexOf(heading);

        assertThat(at).as("heading in:%n%s", String.join("\n", lines)).isNotNegative();
        assertThat(lines.get(at + 1)).isEqualTo(summary);
    }

    @ParameterizedTest
    @MethodSource("exactLines")
    void the_section_holds_the_exact_line(EntityView view, String section, String line) {
        assertThat(section(MarkdownView.render(view), section)).contains(line);
    }

    /** Compared through Ids.normalize, so the case of a marker does not matter. */
    @ParameterizedTest
    @MethodSource("lineParts")
    void one_line_of_the_section_holds_every_part(
            EntityView view, String section, List<String> parts) {
        List<String> wanted = parts.stream().map(Ids::normalize).toList();

        assertThat(section(MarkdownView.render(view), section))
                .anySatisfy(line -> assertThat(Ids.normalize(line)).contains(wanted));
    }

    @ParameterizedTest
    @MethodSource("absentText")
    void the_section_does_not_hold(EntityView view, String section, String text) {
        List<String> lines = section(MarkdownView.render(view), section);

        assertThat(lines).as("section precondition").isNotEmpty();
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(text));
    }

    @Test
    void sections_come_in_the_spec_order() {
        List<String> headings =
                MarkdownView.render(ornekApp01()).lines().filter(l -> l.startsWith("## ")).toList();

        assertThat(headings)
                .containsExactly(
                        "## " + FACTS, "## " + RELATIONS, "## " + SECRETS, "## " + HISTORY);
    }

    /** The lines under {@code ## <name>} (or under the heading for HEAD) up to the next heading. */
    private static List<String> section(String text, String name) {
        String opening = HEAD.equals(name) ? "# " : "## " + name;
        return text.lines()
                .dropWhile(
                        line ->
                                !(HEAD.equals(name)
                                        ? line.startsWith(opening)
                                        : line.equals(opening)))
                .skip(1)
                .takeWhile(line -> !line.startsWith("#"))
                .toList();
    }
}
