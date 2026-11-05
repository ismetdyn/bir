package io.github.ismetdyn.agentkb.server.write;

import static io.github.ismetdyn.agentkb.server.write.WriteRequests.PROD;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.SERVER;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.fact;
import static io.github.ismetdyn.agentkb.server.write.WriteRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.core.model.Source;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ARCHITECTURE §5 step 2, ADR-0022 decision 31 (g): the entity of a write is found by id, alias,
 * hostname or ip, in that order; only when none matches is a new one created. A hostname or ip held
 * by more than one entity is ambiguous: the write is rejected and the hint lists the candidates by
 * id.
 */
class EntityResolverTest {

    private static final Instant T1 = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T09:00:00Z");
    private static final String WEB = "zz-web";
    private static final String WEB_B = "zz-web-b";
    private static final String HOSTNAME = "zz-web.ornek.example";
    private static final String IP = "192.0.2.50";
    private static final String SHARED_IP = "192.0.2.99";
    private static final String ENTITIES = "MATCH (e:Entity) RETURN e.id AS id ORDER BY id";

    @TempDir Path dir;

    private WriteHarness graph;

    @BeforeEach
    void openGraph() throws IOException {
        graph = WriteHarness.open(dir);
        graph.write(
                request(
                        Source.USER,
                        WEB,
                        T1,
                        fact("hostname", HOSTNAME, PROD),
                        fact("ip", IP, PROD),
                        fact("ip", SHARED_IP, PROD)));
        graph.write(
                request(
                        Source.USER,
                        WEB_B,
                        T1,
                        fact("hostname", "zz-web-b.ornek.example", PROD),
                        fact("ip", SHARED_IP, PROD)));
    }

    @AfterEach
    void closeGraph() {
        graph.close();
    }

    private List<Object> entityIds() {
        return graph.rows(ENTITIES, Map.of()).stream().map(row -> row.get("id")).toList();
    }

    private WriteRequest osWrite(String entity, boolean typed) {
        return new WriteRequest(
                Source.USER,
                entity,
                typed ? SERVER : null,
                List.of(fact("os", "rhel 9", PROD)),
                List.of(),
                null,
                null,
                T2);
    }

    @ParameterizedTest(name = "{0}, typed={1}")
    @CsvSource({
        "zz-web.ornek.example, false",
        "zz-web.ornek.example, true",
        "192.0.2.50, false",
        "192.0.2.50, true"
    })
    void a_hostname_or_ip_finds_the_entity_that_holds_it(String hint, boolean typed) {
        List<Object> before = entityIds();

        WriteResult result = graph.write(osWrite(hint, typed));

        assertThat(result.entity()).isEqualTo(WEB);
        assertThat(result.created()).isFalse();
        assertThat(entityIds()).isEqualTo(before);
        assertThat(graph.openFacts(WEB, "os", PROD))
                .singleElement()
                .satisfies(f -> assertThat(f).containsEntry("value", "rhel 9"));
    }

    @ParameterizedTest(name = "typed={0}")
    @CsvSource({"false", "true"})
    void an_ip_held_by_two_entities_is_rejected_naming_both_and_writes_nothing(boolean typed) {
        Map<String, Object> before = graph.state();

        WriteRejectedException rejected =
                catchThrowableOfType(
                        WriteRejectedException.class, () -> graph.write(osWrite(SHARED_IP, typed)));

        assertThat(rejected).as("ambiguous").isNotNull();
        assertThat(rejected.hint()).isNotBlank();
        assertThat(rejected.getMessage() + " " + rejected.hint()).contains(WEB, WEB_B);
        assertThat(graph.state()).isEqualTo(before);
    }

    /**
     * ARCHITECTURE §5 step 2 lists id before hostname, so a write by id never turns ambiguous
     * because another entity holds the same text as its hostname.
     */
    @Test
    void an_id_wins_over_a_hostname_of_another_entity() {
        graph.write(request(Source.USER, "zz-other", T1, fact("hostname", WEB, PROD)));

        WriteResult result = graph.write(osWrite(WEB, false));

        assertThat(result.entity()).isEqualTo(WEB);
        assertThat(result.created()).isFalse();
    }

    @Test
    void a_hint_no_entity_holds_creates_a_new_entity_when_typed() {
        WriteResult result = graph.write(osWrite("zz-new.ornek.example", true));

        assertThat(result.created()).isTrue();
        assertThat(entityIds()).contains(result.entity());
    }
}
