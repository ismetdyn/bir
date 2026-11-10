package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static io.github.ismetdyn.agentkb.server.api.ApiHttp.q;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/secrets?prefix=} (docs/CLIENTS.md §1, invariant 2): names, env, placeholder
 * and whether this machine holds a value; never the value. SecretLeakIT searches it too.
 */
class SecretListApiTest extends RunningServerTestBase {

    @Autowired private WriteService writes;

    private static final String VALUE = "kbtest-fake-list-6a1c0f93e27d";

    @Test
    void the_list_says_a_value_is_here_and_never_holds_it() throws Exception {
        String entity = StoredRecords.server(writes);
        String id = entity + "/prod/kbtest-list-token";
        assertThat(new SecretHttp(port).put(id, VALUE).statusCode()).isEqualTo(204);

        HttpResponse<String> reply =
                new ApiHttp(port).get("/api/v1/secrets?prefix=" + q(entity + "/"));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(reply.body()).doesNotContain(VALUE);
        JsonNode secrets = JsonMapper.shared().readTree(reply.body()).get("secrets");
        JsonNode mine = null;
        for (JsonNode secret : secrets) {
            assertThat(secret.get("id").asString()).startsWith(entity + "/");
            if (secret.get("id").asString().equals(id)) {
                mine = secret;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("hasValue").asBoolean()).isTrue();
        assertThat(mine.get("placeholder").asString()).isEqualTo("kb:secret/" + id);
        assertThat(mine.get("name").asString()).isEqualTo("kbtest-list-token");
    }

    /** Each listed flag matches what GET of the value answers on this machine. */
    @Test
    void has_value_is_whether_get_returns_a_value() throws Exception {
        HttpResponse<String> reply = new ApiHttp(port).get("/api/v1/secrets");
        SecretHttp values = new SecretHttp(port);

        JsonNode secrets = JsonMapper.shared().readTree(reply.body()).get("secrets");

        assertThat(secrets).isNotEmpty();
        for (JsonNode secret : secrets) {
            int status = values.get(secret.get("id").asString()).statusCode();
            assertThat(secret.get("hasValue").asBoolean())
                    .as(secret.get("id").asString())
                    .isEqualTo(status == 200);
        }
    }

    /** Lead H6 review 6: the secret list is paged like the other lists. */
    @Test
    void the_list_takes_a_limit_and_counts_the_rest() throws Exception {
        JsonNode all =
                JsonMapper.shared()
                        .readTree(new ApiHttp(port).get("/api/v1/secrets?limit=500").body());
        JsonNode one =
                JsonMapper.shared()
                        .readTree(new ApiHttp(port).get("/api/v1/secrets?limit=1").body());

        assertThat(one.get("secrets")).hasSize(1);
        assertThat(one.get("more").asInt()).isEqualTo(all.get("secrets").size() - 1);
        assertRefused(new ApiHttp(port).get("/api/v1/secrets?limit=501"), 400);
    }

    @Test
    void an_over_long_prefix_is_refused_without_echo() throws Exception {
        String prefix = "zzh6" + "p".repeat(700);

        assertRefused(new ApiHttp(port).get("/api/v1/secrets?prefix=" + prefix), 400, prefix);
    }
}
