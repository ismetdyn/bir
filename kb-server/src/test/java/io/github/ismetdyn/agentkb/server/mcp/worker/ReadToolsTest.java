package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.DATA_HEADER;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertCurrentBeforeHistory;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.heading;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.isMarked;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.resultLines;
import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.section;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * kb_read and kb_related over the seed through the real /mcp endpoint. Verbatim lines are the SPEC
 * §6.6 example; the rest is seed/README.md "Bilerek konulan durumlar" and eval q02, q05, q08, q13,
 * q18. Where the SPEC fixes no exact form, a row names only the parts that must be on one line.
 */
class ReadToolsTest extends RunningServerTestBase {

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    /** An id, an alias (q02) or another casing resolves to the record; ids go through Ids. */
    @ParameterizedTest(name = "kb_read {0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "ornek-f5          | # ornek-lb · Network · current",
                "app01             | # ornek-app-01 · Server · current",
                "ORNEK-PROD-ORTAMI | # ornek-prod-ortami · Environment · current",
                "ornek-app-00      | # ornek-app-00 · Server · archived",
                "ornek-db          | # ornek-db · Database · observed"
            })
    void read_resolves_the_record_and_names_its_state(String id, String expected) throws Exception {
        String text = read(id);

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        assertThat(heading(text)).as(text).contains(expected);
    }

    /** SPEC §6.6 lines, verbatim: the conflict is shown while the user value stays current. */
    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "ornek-app-01 | Bilgiler | - hostname: ornek-app-01.ornek.example @prod · user, 2026-05-10",
                "ornek-app-01 | Bilgiler | - ip: 192.0.2.21 @prod · user, 2026-05-10",
                "ornek-app-01 | Bilgiler | - ip: 192.0.2.25 @prod · agent, 2026-10-28 · ÇELİŞKİ (192.0.2.21 korunuyor)",
                "ornek-app-01 | Geçmiş   | - ip: 192.0.2.20 @prod · user, 2026-01-15 → 2026-05-10"
            })
    void read_writes_the_spec_lines(String id, String section, String line) throws Exception {
        assertThat(section(read(id), section)).contains(line);
    }

    /**
     * One line of the section holds every part (compared through Ids.normalize, so case does not
     * matter). Port → target, source file, secret name and placeholder, ended relation (q18),
     * unverified fact from a document, both relation directions.
     */
    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "ornek-app-01 | Bilgiler  | port: 8443/https → ornek-proje @prod · scan, 2026-10-29;application-prod.yml:2",
                "ornek-app-01 | Secret'lar | deploy-ssh-key @prod;kb:secret/ornek-app-01/prod/deploy-ssh-key",
                "ornek-proje  | Geçmiş    | DEPLOYED_ON;ornek-app-00;2026-01-15 → 2026-05-10",
                "ornek-proje  | Bilgiler  | convention: Yeniden başlatma;doc:ded48257b6f3;doğrulanmamış",
                "ornek-proje  | İlişkiler | DEPLOYED_ON → ornek-app-01",
                "ornek-proje  | İlişkiler | RUNS;ornek-app-01",
                "ornek-lb     | Secret'lar | panel-admin @prod;kb:secret/ornek-lb/prod/panel-admin"
            })
    void read_puts_the_parts_on_one_line(String id, String section, String parts) throws Exception {
        List<String> wanted = Stream.of(parts.split(";")).map(Ids::normalize).toList();

        assertThat(section(read(id), section))
                .as("%s ## %s", id, section)
                .anySatisfy(line -> assertThat(Ids.normalize(line)).contains(wanted));
    }

    /** Closed facts and ended relations stay out of the current sections. */
    @ParameterizedTest(name = "{0} {1} has no {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "ornek-app-01 | Bilgiler  | 192.0.2.20",
                "ornek-proje  | İlişkiler | ornek-app-00"
            })
    void read_keeps_history_out_of_current_sections(String id, String section, String absent)
            throws Exception {
        String text = read(id);

        assertThat(section(text, "Geçmiş")).as("history precondition").isNotEmpty();
        assertThat(section(text, section)).noneSatisfy(line -> assertThat(line).contains(absent));
    }

    /** Eval q18, SPEC §6.3: the ended DEPLOYED_ON is listed, marked, after current relations. */
    @Test
    void related_marks_the_ended_relation_old_after_current_ones() throws Exception {
        String text = mcp.text("kb_related", Map.of("id", "ornek-proje"));

        assertThat(text.lines().findFirst()).contains(DATA_HEADER);
        assertThat(resultLines(text))
                .as(text)
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("ornek-app-00", "eski (2026-01-15 → 2026-05-10)"))
                .anySatisfy(
                        line -> {
                            assertThat(line).contains("DEPLOYED_ON", "ornek-app-01");
                            assertThat(isMarked(line)).as(line).isFalse();
                        });
        assertCurrentBeforeHistory(text);
    }

    /** Eval q05: what is affected if the load balancer goes down. */
    @Test
    void related_follows_incoming_relations_up_to_depth_three() throws Exception {
        String text =
                mcp.text("kb_related", Map.of("id", "ornek-lb", "direction", "in", "depth", 3));

        assertThat(String.join("\n", resultLines(text))).contains("ornek-app-01", "ornek-proje");
    }

    /** Eval q08: the relation filter keeps one type and one direction. */
    @Test
    void related_filters_by_relation_type_and_direction() throws Exception {
        String text =
                mcp.text(
                        "kb_related",
                        Map.of("id", "ornek-proje", "rel", "DEPENDS_ON", "direction", "out"));

        assertThat(resultLines(text))
                .as(text)
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("ornek-db"));
    }

    private String read(String id) throws IOException, InterruptedException {
        return mcp.text("kb_read", Map.of("id", id));
    }
}
