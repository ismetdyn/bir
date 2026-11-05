package io.github.ismetdyn.agentkb.server.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * docs/SECRETS.md §2 and §4: {@code PUT /api/v1/secrets/<id>} writes {@code secret_value} on the
 * Secret with that id, or creates the Secret {@code <entity>/<env>/<name>} under its entity; the
 * value is never in the reply. {@code GET} returns exactly the value and nothing else. The values
 * are fake.
 */
class SecretApiTest extends RunningServerTestBase {

    private static final String SEED_ID = "ornek-lb/prod/panel-admin";
    private static final String NEW_PREFIX = "ornek-app-01/prod/kbtest-";
    private static final String NEW_ID = NEW_PREFIX + "new-token";
    private static final String VALUE = "kbtest-FAKE-api-3e9a71c0";
    private static final String NODE =
            "MATCH (s:Secret {id: $id}) RETURN s.name AS name, s.env AS env,"
                    + " [k IN keys(s) WHERE k <> 'secret_value' | toString(s[k])] AS others";

    @Autowired private EmbeddedNeo4jStore store;

    private SecretHttp secrets;

    @BeforeEach
    void client() {
        secrets = new SecretHttp(port);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                VALUE,
                "-----FAKE KEY-----\nkbtest şİığüöç €\n-----END FAKE-----\n",
                "kbtest-FAKE \"quoted\" back\\slash %41 <tag>"
            })
    void a_put_value_comes_back_exactly(String value) throws Exception {
        HttpResponse<String> put = secrets.put(SEED_ID, value);
        HttpResponse<String> get = secrets.get(SEED_ID);

        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(put.body()).isEmpty();
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(value);
    }

    /** SECRETS.md §2: the placeholder stays, the old value is not kept anywhere on the node. */
    @Test
    void a_second_put_replaces_the_value_and_keeps_no_old_copy() throws Exception {
        String old = VALUE + "-old";
        secrets.put(SEED_ID, old);

        secrets.put(SEED_ID, VALUE);

        assertThat(secrets.get(SEED_ID).body()).isEqualTo(VALUE);
        Map<String, Object> node = node(SEED_ID);
        assertThat(node).containsEntry("name", "panel-admin").containsEntry("env", "prod");
        assertThat(String.valueOf(node.get("others"))).doesNotContain(old).doesNotContain(VALUE);
    }

    @Test
    void a_put_on_a_new_id_creates_the_secret_under_its_entity() throws Exception {
        HttpResponse<String> put = secrets.put(NEW_ID, VALUE);

        assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
        assertThat(secrets.get(NEW_ID).body()).isEqualTo(VALUE);
        assertThat(node(NEW_ID))
                .containsEntry("name", "kbtest-new-token")
                .containsEntry("env", "prod");
        String owner = McpClient.open(port).text("kb_read", Map.of("id", "ornek-app-01"));
        assertThat(owner).contains("kb:secret/" + NEW_ID).doesNotContain(VALUE);
    }

    @Test
    void an_empty_value_is_refused_and_the_old_one_stays() throws Exception {
        secrets.put(SEED_ID, VALUE);

        HttpResponse<String> put = secrets.put(SEED_ID, "");

        assertThat(put.statusCode()).isEqualTo(400);
        assertThat(secrets.get(SEED_ID).body()).isEqualTo(VALUE);
    }

    private Map<String, Object> node(String id) {
        List<Map<String, Object>> rows =
                store.read(tx -> tx.execute(NODE, Map.of("id", id)).stream().toList());
        assertThat(rows).as("Secret %s", id).hasSize(1);
        return rows.getFirst();
    }

    // The context is shared: other tests count the seed's three Secret nodes and set their values.
    @AfterEach
    void restoreSecrets() {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret) WHERE s.id STARTS WITH $prefix DETACH DELETE s",
                        Map.of("prefix", NEW_PREFIX));
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret {id: $id}) REMOVE s.secret_value", Map.of("id", SEED_ID));
    }
}
