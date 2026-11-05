package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0020: the one way out of a secret value sits behind OriginGuard. A web page (DNS rebinding:
 * foreign Host, or any Origin) neither reads a stored value nor overwrites it. OriginGuardTest
 * covers the status on every path; this one covers the value.
 */
class SecretEndpointGuardTest extends SecurityTestBase {

    private static final String ID = "ornek-app-01/prod/deploy-ssh-key";
    private static final String PATH = SecretHttp.PATH + ID;
    private static final String STORED = "kbtest-FAKE-guard-0c4f8e21";
    private static final String FORGED = "kbtest-FAKE-forged-77aa10b3";
    private static final String TEXT = "Content-Type: text/plain; charset=UTF-8";

    @Autowired private EmbeddedNeo4jStore store;

    private SecretHttp secrets;

    @BeforeEach
    void storeValue() throws Exception {
        secrets = new SecretHttp(port);
        assertThat(secrets.put(ID, STORED).statusCode()).as("fixture").isEqualTo(204);
    }

    @Test
    void a_browser_request_does_not_read_the_value() throws Exception {
        Reply withOrigin =
                RawHttp.send(
                        port, HTTP_1_1, "GET", PATH, "", host(), "Origin: http://attacker.example");
        Reply forgedHost =
                RawHttp.send(port, HTTP_1_1, "GET", PATH, "", "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(withOrigin.body() + forgedHost.body()).doesNotContain(STORED);
    }

    @Test
    void a_browser_request_does_not_overwrite_the_value() throws Exception {
        Reply withOrigin =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "PUT",
                        PATH,
                        FORGED,
                        host(),
                        TEXT,
                        "Origin: http://attacker.example");
        Reply forgedHost =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "PUT",
                        PATH,
                        FORGED,
                        "Host: attacker.example:" + port,
                        TEXT);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(secrets.get(ID).body()).isEqualTo(STORED);
    }

    // The context is shared; leave the seed Secret as other tests expect it.
    @AfterEach
    void removeValue() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret {id: $id}) REMOVE s.secret_value", Map.of("id", ID));
    }
}
