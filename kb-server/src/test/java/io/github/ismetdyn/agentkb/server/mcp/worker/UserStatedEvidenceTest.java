package io.github.ismetdyn.agentkb.server.mcp.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC §7 rule 7, ADR-0022 decision 29 (a): user_stated holds only when the evidence appears word
 * for word in one user message of the last two hours and contains the written value; then that
 * message holds the value too. Otherwise the source is agent. Measured before the decision: an
 * evidence of "the" or one letter was enough. The PromptRing is shared by the server tests, so
 * every row uses its own zz-g7-ev* words.
 */
class UserStatedEvidenceTest extends RunningServerTestBase {

    private static final String FACTS =
            "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) RETURN f.key AS key,"
                    + " f.value AS value, f.source AS source";
    private static final Duration OUT_OF_WINDOW = Duration.ofHours(3);
    private static final Map<String, String> AGENT = Map.of("kind", "command", "ref", "test");

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private PromptRing promptRing;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    /** entity, messages (recorded now unless marked), evidence, expected source. */
    private static Stream<Arguments> evidence() {
        return Stream.of(
                arguments(
                        "the value in the evidence, the evidence in a recent message",
                        "zz-g7-ev1",
                        List.of("Bilgi: zz-g7-ev1 sunucusu zz-g7-ev1.ornek.example adındadır."),
                        "zz-g7-ev1 sunucusu zz-g7-ev1.ornek.example adındadır",
                        "user_stated"),
                arguments(
                        "a common word as evidence",
                        "zz-g7-ev2",
                        List.of("the zz-g7-ev2 host is zz-g7-ev2.ornek.example"),
                        "the",
                        "agent"),
                arguments(
                        "evidence in one message, the value only in another",
                        "zz-g7-ev3",
                        List.of("zz-g7-ev3 için not alalım", "zz-g7-ev3.ornek.example"),
                        "zz-g7-ev3 için not alalım",
                        "agent"),
                arguments(
                        "the value in the message but not in the evidence",
                        "zz-g7-ev4",
                        List.of("zz-g7-ev4 sunucusunun adı zz-g7-ev4.ornek.example olsun"),
                        "zz-g7-ev4 sunucusunun adı",
                        "agent"),
                arguments(
                        "evidence with the value but split over two messages",
                        "zz-g7-ev5",
                        List.of("zz-g7-ev5 sunucusunun adı", "zz-g7-ev5.ornek.example olsun"),
                        "zz-g7-ev5 sunucusunun adı zz-g7-ev5.ornek.example",
                        "agent"),
                arguments(
                        "the evidence in a message older than two hours",
                        "zz-g7-ev6",
                        List.of("OLD:zz-g7-ev6 adı zz-g7-ev6.ornek.example"),
                        "zz-g7-ev6 adı zz-g7-ev6.ornek.example",
                        "agent"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evidence")
    void user_stated_needs_the_value_inside_the_users_own_recent_words(
            String why, String entity, List<String> messages, String evidence, String expected)
            throws Exception {
        Instant now = Instant.now();
        for (String message : messages) {
            if (message.startsWith("OLD:")) {
                promptRing.record(message.substring("OLD:".length()), now.minus(OUT_OF_WINDOW));
            } else {
                promptRing.record(message, now);
            }
        }

        String reply =
                remember(
                        entity,
                        List.of(fact("hostname", entity + ".ornek.example")),
                        Map.of("kind", "user_stated", "evidence", evidence));

        assertThat(reply).as(why).doesNotStartWith("{\"error\"");
        assertThat(facts(entity))
                .as(why)
                .singleElement()
                .satisfies(f -> assertThat(f.get("source")).as(why).isEqualTo(expected));
    }

    /** One evidence for two facts: a value it does not hold is not user_stated. */
    @Test
    void a_fact_whose_value_the_evidence_lacks_is_not_user_stated() throws Exception {
        String entity = "zz-g7-ev7";
        String hostname = "zz-g7-ev7.ornek.example";
        String ip = "192.0.2.77";
        promptRing.record("zz-g7-ev7 adı " + hostname + ", ip " + ip, Instant.now());

        remember(
                entity,
                List.of(fact("hostname", hostname), fact("ip", ip)),
                Map.of("kind", "user_stated", "evidence", "zz-g7-ev7 adı " + hostname));

        assertThat(facts(entity))
                .filteredOn(f -> "ip".equals(f.get("key")))
                .singleElement()
                .satisfies(f -> assertThat(f.get("source")).isNotEqualTo("user_stated"));
    }

    /**
     * Review finding: a value must be a whole token of the evidence and the evidence whole tokens
     * of the message; 192.0.2.15 is not stated by "192.0.2.150", nor 543 by "5432/tcp".
     */
    private static Stream<Arguments> fragments() {
        return Stream.of(
                arguments(
                        "the evidence is a fragment of a longer token in the message",
                        "zz-g7-ev9",
                        "zz-g7-ev9 ip 192.0.2.150",
                        "zz-g7-ev9 ip 192.0.2.15",
                        fact("ip", "192.0.2.15"),
                        "agent"),
                arguments(
                        "the value is a fragment of a longer token in the evidence",
                        "zz-g7-ev10",
                        "zz-g7-ev10 ip 192.0.2.150",
                        "zz-g7-ev10 ip 192.0.2.150",
                        fact("ip", "192.0.2.15"),
                        "agent"),
                arguments(
                        "a port number inside a longer port",
                        "zz-g7-ev11",
                        "zz-g7-ev11 postgres 5432/tcp portunda",
                        "zz-g7-ev11 postgres 5432/tcp portunda",
                        fact("port", "543"),
                        "agent"),
                arguments(
                        "a whole token at the end of a sentence",
                        "zz-g7-ev12",
                        "zz-g7-ev12 ip adresi 192.0.2.15.",
                        "zz-g7-ev12 ip adresi 192.0.2.15",
                        fact("ip", "192.0.2.15"),
                        "user_stated"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fragments")
    void a_fragment_of_a_longer_token_is_not_what_the_user_stated(
            String why,
            String entity,
            String message,
            String evidence,
            Map<String, String> fact,
            String expected)
            throws Exception {
        promptRing.record(message, Instant.now());

        String reply =
                remember(
                        entity, List.of(fact), Map.of("kind", "user_stated", "evidence", evidence));

        assertThat(reply).as(why).doesNotStartWith("{\"error\"");
        assertThat(facts(entity))
                .as(why)
                .singleElement()
                .satisfies(f -> assertThat(f.get("source")).as(why).isEqualTo(expected));
    }

    /**
     * ADR-0022 decision 30: the target is part of a port's value, so decision 29 binds it too. The
     * user said the port, never evil-db; the agent may not lift that target to user_stated.
     */
    @ParameterizedTest(name = "evidence {0} -> {1}")
    @CsvSource({
        "'zz-g7-ev13 postgres 5432/tcp portunda', agent",
        "'zz-g7-ev13 postgres 5432/tcp portunda zz-g7-ev13-db', user_stated"
    })
    void a_port_target_the_evidence_lacks_is_not_user_stated(String evidence, String expected)
            throws Exception {
        String entity = "zz-g7-ev13-" + expected;
        String target = "zz-g7-ev13-db";
        remember(target, List.of(fact("hostname", "zz-g7-ev13-db.ornek.example")), AGENT);
        promptRing.record(evidence, Instant.now());

        String reply =
                remember(
                        entity,
                        List.of(
                                Map.of(
                                        "key",
                                        "port",
                                        "value",
                                        "5432/tcp",
                                        "env",
                                        "prod",
                                        "target",
                                        target)),
                        Map.of("kind", "user_stated", "evidence", evidence));

        assertThat(reply).doesNotStartWith("{\"error\"");
        assertThat(facts(entity))
                .singleElement()
                .satisfies(f -> assertThat(f.get("source")).isEqualTo(expected));
    }

    private static Map<String, String> fact(String key, String value) {
        return Map.of("key", key, "value", value, "env", "prod");
    }

    private String remember(
            String entity, List<Map<String, String>> facts, Map<String, String> source)
            throws IOException, InterruptedException {
        return mcp.text(
                "kb_remember",
                Map.of("entity", entity, "type", "server", "facts", facts, "source", source));
    }

    private List<Map<String, Object>> facts(String entity) {
        return store.read(tx -> tx.execute(FACTS, Map.of("id", entity)).stream().toList());
    }
}
