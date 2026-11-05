package io.github.ismetdyn.agentkb.server.mcp.worker;

import static io.github.ismetdyn.agentkb.server.mcp.worker.ToolOutput.assertErrorWithHint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0022 decision 31 (c), invariant 2: evidence, ref and proposal text reach the snapshot and
 * git, so a secret-looking value there is rejected like one in a fact value: {error, hint}, nothing
 * written, the secret in no answer and in no graph property. Each test fails when the check on its
 * field is removed. Secrets are put together from parts; none is real.
 */
class EvidenceSecretTest extends RunningServerTestBase {

    private static final String TOKEN = "ghp" + "_7a6B5c4D3e2F1g0H9i8J7k6L5m4N3o2P1q0R";
    private static final String PGP_KEY = "-----BEGIN PGP " + "PRIVATE KEY BLOCK-----";
    private static final String BASIC = "Authorization: Basic " + "YWRtaW46UzNjcjN0UGFzcw==";
    // Its own secret part: a RED row that stores it must not fail the URL row (shared graph).
    private static final String ENCODED_PART = "Zz8Enc0dedPw";
    private static final String ENCODED = "password%3D" + ENCODED_PART;
    private static final String URL_PASSWORD =
            "https://zzuser:" + "Zz9Sup3rS3cret" + "@git.ornek.example/app.git";
    private static final String SECRET_HINT = "kb secret set";
    private static final String PROPOSALS = "MATCH (p:Proposal) RETURN count(p) AS n";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private PromptRing promptRing;

    private McpClient mcp;

    @BeforeEach
    void openSession() throws IOException, InterruptedException {
        mcp = McpClient.open(port);
    }

    /** why, entity, source of kb_remember, the secret part. */
    private static Stream<Arguments> rememberSources() {
        return Stream.of(
                arguments(
                        "token in evidence",
                        "zz-g7-sec1",
                        Map.of("kind", "doc", "evidence", "runbook says " + TOKEN),
                        TOKEN),
                arguments(
                        "password URL in ref",
                        "zz-g7-sec2",
                        Map.of("kind", "code", "ref", URL_PASSWORD),
                        "Zz9Sup3rS3cret"),
                arguments(
                        "PGP private key header in evidence",
                        "zz-g7-sec3",
                        Map.of("kind", "doc", "evidence", "key file starts " + PGP_KEY),
                        PGP_KEY),
                arguments(
                        "Basic credentials in evidence",
                        "zz-g7-sec4",
                        Map.of("kind", "command", "evidence", "curl -H '" + BASIC + "'"),
                        "YWRtaW46UzNjcjN0UGFzcw=="),
                arguments(
                        "URL-encoded password in evidence",
                        "zz-g7-sec5",
                        Map.of("kind", "doc", "evidence", "query had " + ENCODED),
                        ENCODED_PART));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rememberSources")
    void a_secret_in_evidence_or_ref_rejects_the_write(
            String why, String entity, Map<String, String> source, String secret) throws Exception {
        String reply =
                mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                entity,
                                "type",
                                "server",
                                "facts",
                                List.of(
                                        Map.of(
                                                "key",
                                                "hostname",
                                                "value",
                                                entity + ".ornek.example",
                                                "env",
                                                "prod")),
                                "source",
                                source));

        assertThat(reply).as(why + ": rejected").startsWith("{\"error\"");
        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString()).as(why).contains(SECRET_HINT);
        assertThat(reply).as(why).doesNotContain(secret);
        assertThat(count("MATCH (e:Entity {id: $s}) RETURN count(e) AS n", entity))
                .as(why + ": nothing written")
                .isZero();
        assertThat(inGraph(secret)).as(why + ": secret in the graph").isZero();
    }

    /** The user's own words do not make a secret storable (rule 7 does not bypass rule 8). */
    @ParameterizedTest(name = "user_stated: {0}")
    @MethodSource("rememberSources")
    void a_secret_in_user_stated_evidence_the_user_wrote_is_rejected_too(
            String why, String entity, Map<String, String> source, String secret) throws Exception {
        String value = entity + "-us.ornek.example";
        String evidence =
                "adı " + value + " ve " + source.getOrDefault("evidence", source.get("ref"));
        promptRing.record(evidence, Instant.now());

        String reply =
                mcp.text(
                        "kb_remember",
                        Map.of(
                                "entity",
                                entity + "-us",
                                "type",
                                "server",
                                "facts",
                                List.of(Map.of("key", "hostname", "value", value, "env", "prod")),
                                "source",
                                Map.of("kind", "user_stated", "evidence", evidence)));

        assertThat(reply).as(why + ": rejected").startsWith("{\"error\"");
        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString()).as(why).contains(SECRET_HINT);
        assertThat(reply).as(why).doesNotContain(secret);
        assertThat(inGraph(secret)).as(why + ": secret in the graph").isZero();
    }

    /** why, field of kb_propose, its text. */
    private static Stream<Arguments> proposalFields() {
        return Stream.of(
                arguments("token in content", "content", "Kural: deploy " + TOKEN + " ile", TOKEN),
                arguments("token in evidence", "evidence", "seen in ci.yml: " + TOKEN, TOKEN),
                arguments("PGP key in content", "content", "Anahtar: " + PGP_KEY, PGP_KEY),
                arguments(
                        "Basic credentials in evidence",
                        "evidence",
                        "curl -H '" + BASIC + "'",
                        "YWRtaW46UzNjcjN0UGFzcw=="),
                arguments(
                        "URL-encoded password in content",
                        "content",
                        "Bağlantı: " + ENCODED,
                        ENCODED_PART));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("proposalFields")
    void a_secret_in_proposal_content_or_evidence_opens_no_proposal(
            String why, String field, String text, String secret) throws Exception {
        long before = count(PROPOSALS, "");
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("kind", "decision");
        arguments.put("content", "Kural: zz-g7 servisleri systemd ile çalışır.");
        arguments.put("evidence", "zz-g7 three projects do it");
        arguments.put(field, text);

        String reply = mcp.text("kb_propose", arguments);

        assertThat(reply).as(why + ": rejected").startsWith("{\"error\"");
        assertErrorWithHint(reply);
        assertThat(JSON.readTree(reply).path("hint").asString()).as(why).contains(SECRET_HINT);
        assertThat(reply).as(why).doesNotContain(secret);
        assertThat(count(PROPOSALS, "")).as(why + ": no proposal").isEqualTo(before);
        assertThat(inGraph(secret)).as(why + ": secret in the graph").isZero();
    }

    /** Nodes and edges with a property value that holds {@code secret}, read past CypherGuard. */
    private long inGraph(String secret) {
        return store.read(
                tx ->
                        Stream.concat(
                                        tx.getAllNodes().stream().map(n -> n.getAllProperties()),
                                        tx.getAllRelationships().stream()
                                                .map(r -> r.getAllProperties()))
                                .filter(
                                        props ->
                                                props.values().stream()
                                                        .anyMatch(
                                                                v ->
                                                                        String.valueOf(v)
                                                                                .contains(secret)))
                                .count());
    }

    private long count(String cypher, String value) {
        return store.read(tx -> (Long) tx.execute(cypher, Map.of("s", value)).next().get("n"));
    }
}
