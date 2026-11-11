package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0020 for every H6 route (docs/CLIENTS.md §1): a web page (any Origin, or a foreign Host after
 * DNS rebinding) gets 403 before the endpoint reads or writes anything. Writes that got through
 * would create the entity or alias, so the guard is checked by its effect too.
 */
class ApiEndpointGuardTest extends SecurityTestBase {

    private static final String ORIGIN = "Origin: http://attacker.example";

    @Autowired private KnowledgeQueries queries;

    @ParameterizedTest(name = "{0} {1} {3}")
    @CsvSource(
            delimiter = '|',
            value = {
                "GET|/api/v1/search?q=ornek||application/json",
                "GET|/api/v1/stale||application/json",
                "GET|/api/v1/entities/ornek-app-01||application/json",
                "GET|/api/v1/entities/ornek-app-01||text/markdown",
                "POST|/api/v1/cypher|{\"query\": \"RETURN 'ID' AS id\"}|application/json",
                "POST|/api/v1/entities|{\"id\": \"ID\", \"type\": \"server\"}|application/json",
                "POST|/api/v1/entities/ornek-app-01/facts|{\"facts\": [{\"key\": \"note\", \"value\": \"ID\"}]}|application/json",
                "POST|/api/v1/entities/ornek-app-01/relations|{\"relations\": [{\"type\": \"behind\", \"target\": \"ID\"}]}|application/json",
                "PATCH|/api/v1/entities/ornek-app-01|{\"aliases\": [\"ID\"]}|application/json",
                "POST|/api/v1/facts/ID/close|{}|application/json",
                "POST|/api/v1/relations/ID/close|{}|application/json",
                "POST|/api/v1/entities/ID/archive|{}|application/json",
                "POST|/api/v1/entities/ID/unarchive|{}|application/json",
                "GET|/api/v1/conflicts||application/json",
                "POST|/api/v1/conflicts/ID/resolve|{\"resolution\": \"keep-old\"}|application/json",
                "GET|/api/v1/proposals||application/json",
                "POST|/api/v1/proposals/ID/accept|{}|application/json",
                "POST|/api/v1/proposals/ID/reject|{\"note\": \"ID\"}|application/json",
                "GET|/api/v1/activity||application/json",
                "GET|/api/v1/activity/ID||application/json",
                "GET|/api/v1/schema||application/json",
                "GET|/api/v1/entities?type=server||application/json",
                "GET|/api/v1/secrets?prefix=ornek||application/json",
                "POST|/api/v1/scan|{\"path\": \"/tmp/ID\"}|application/json"
            })
    void a_browser_request_is_refused_before_the_endpoint(
            String method, String path, String body, String accept) throws Exception {
        String id = "zz-h6-guard-" + UUID.randomUUID();
        String sent = body == null ? "" : body.replace("ID", id);
        String target = path.replace("ID", id);
        String[] headers = {CONTENT_TYPE, "Accept: " + accept};

        Reply withOrigin = send(method, target, sent, host(), ORIGIN, headers);
        Reply forgedHost =
                send(method, target, sent, "Host: attacker.example:" + port, null, headers);

        assertThat(withOrigin.status()).as(withOrigin.body()).isEqualTo(403);
        assertThat(forgedHost.status()).as(forgedHost.body()).isEqualTo(403);
        assertThat(withOrigin.body() + forgedHost.body())
                .doesNotContain("ornek-app-01")
                .doesNotContain(id);
        assertThat(queries.entity(id)).isEmpty();
        assertThat(queries.entity("ornek-app-01").orElseThrow().aliases()).doesNotContain(id);
    }

    private Reply send(
            String method, String target, String body, String host, String origin, String[] more)
            throws Exception {
        String[] lines =
                origin == null
                        ? new String[] {host, more[0], more[1]}
                        : new String[] {host, origin, more[0], more[1]};
        return RawHttp.send(port, HTTP_1_1, method, target, body, lines);
    }
}
