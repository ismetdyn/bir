package io.github.ismetdyn.agentkb.server.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The refusals of the secret endpoint. A value this machine does not have and an id that does not
 * exist answer the same 404, with nothing about the id or the server in the body (invariant 2,
 * best-practices "Hata yönetimi"). A PUT whose id is not {@code <entity>/<env>/<name>} creates
 * nothing (security.md: validated at the boundary).
 */
class SecretApiRejectionTest extends RunningServerTestBase {

    private static final String NO_VALUE_ID = "ornek-proje/prod/spring.datasource.password";
    private static final String UNKNOWN_ID = "ornek-app-01/prod/kbtest-never-set";
    private static final String VALUE = "kbtest-FAKE-reject-5b7d20e4";
    private static final List<String> INTERNAL_DETAIL =
            List.of("Exception", "\tat ", "org.", "java.", "/home/", "MATCH ", "trace\"");

    @Autowired private EmbeddedNeo4jStore store;

    private SecretHttp secrets;

    @BeforeEach
    void client() {
        secrets = new SecretHttp(port);
    }

    @Test
    void an_unknown_id_is_404_without_detail() throws Exception {
        assertNotFoundWithoutDetail(secrets.get(UNKNOWN_ID), UNKNOWN_ID);
    }

    /** SECRETS.md §4 "Başka bir bilgisayar": the Secret came by sync, its value did not. */
    @Test
    void a_secret_without_a_value_on_this_machine_is_404_without_detail() throws Exception {
        store.database()
                .executeTransactionally(
                        "MATCH (s:Secret {id: $id}) REMOVE s.secret_value",
                        Map.of("id", NO_VALUE_ID));

        assertNotFoundWithoutDetail(secrets.get(NO_VALUE_ID), NO_VALUE_ID);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "kbtest-no-slashes",
                "ornek-app-01/kbtest-two-parts",
                "ornek-app-01//kbtest-empty-env",
                "ornek-app-01/prod/kbtest-four/parts"
            })
    void a_put_with_a_malformed_id_creates_nothing(String id) throws Exception {
        HttpResponse<String> put = secrets.put(id, VALUE);

        // 400 from the endpoint; an empty segment may already be refused by routing.
        assertThat(put.statusCode()).as(put.body()).isBetween(400, 499);
        assertThat(put.body()).doesNotContain(VALUE).doesNotContain(INTERNAL_DETAIL);
        assertThat(secretCount(id)).isZero();
    }

    private void assertNotFoundWithoutDetail(HttpResponse<String> reply, String id) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(404);
        assertThat(reply.body()).doesNotContain(id).doesNotContain(INTERNAL_DETAIL);
    }

    private long secretCount(String id) {
        return store.read(
                tx ->
                        (Long)
                                tx.execute(
                                                "MATCH (s:Secret {id: $id}) RETURN count(s) AS n",
                                                Map.of("id", id))
                                        .next()
                                        .get("n"));
    }
}
