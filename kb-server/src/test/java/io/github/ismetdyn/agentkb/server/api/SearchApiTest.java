package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static io.github.ismetdyn.agentkb.server.api.ApiHttp.q;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteResult;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/search} and {@code /api/v1/stale} (docs/CLIENTS.md §1): the ADR-0017 limit,
 * the invariant 12 order and marks; a refusal is {@code {error, hint}} without the request value.
 */
class SearchApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private WriteService writes;

    @Test
    void search_lists_current_before_history_and_marks_the_old_value() throws Exception {
        String word = word();
        String closed = "zz-h6-old-" + UUID.randomUUID();
        WriteResult old = note(closed, word + " old");
        writes.edits().closeFact(old.added().getFirst());
        String current = "zz-h6-new-" + UUID.randomUUID();
        note(current, word + " new");

        JsonNode answer = json(http().get("/api/v1/search?q=" + q(word)));

        JsonNode hits = answer.get("hits");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).get("id").asString()).isEqualTo(current);
        assertThat(hits.get(0).get("tier").asString()).isEqualTo("current");
        assertThat(hits.get(1).get("id").asString()).isEqualTo(closed);
        assertThat(hits.get(1).get("tier").asString()).isEqualTo("history");
        assertThat(hits.get(1).get("snippet").asString()).contains("eski (");
        assertThat(answer.get("more").asInt()).isZero();
    }

    @Test
    void search_shows_at_most_limit_hits_and_counts_the_rest() throws Exception {
        String word = word();
        for (int i = 0; i < 3; i++) {
            note("zz-h6-many-" + UUID.randomUUID(), word + " " + i);
        }

        JsonNode answer = json(http().get("/api/v1/search?q=" + q(word) + "&limit=2"));

        assertThat(answer.get("hits")).hasSize(2);
        assertThat(answer.get("more").asInt()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"&limit=21", "&limit=0", "&limit=x"})
    void search_refuses_a_limit_outside_1_to_20(String limit) throws Exception {
        assertRefused(http().get("/api/v1/search?q=ornek" + limit), 400);
    }

    @Test
    void search_refuses_an_empty_or_over_long_query_without_repeating_it() throws Exception {
        String longQuery = "zzh6" + "q".repeat(600);

        assertRefused(http().get("/api/v1/search?q=%20"), 400);
        assertRefused(http().get("/api/v1/search"), 400);
        assertRefused(http().get("/api/v1/search?q=" + longQuery), 400, longQuery);
    }

    @Test
    void stale_lists_current_facts_last_seen_before_the_days() throws Exception {
        String id = "zz-h6-stale-" + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        id,
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now().minusSeconds(5)));

        JsonNode answer = json(http().get("/api/v1/stale?days=0&limit=500"));

        assertThat(answer.get("hits").toString()).contains("\"id\":\"" + id + "\"");
        assertRefused(http().get("/api/v1/stale?days=-1"), 400);
    }

    /** Lead H6 review 5: one line of at most 300 characters, like kb_search (ResultList). */
    @Test
    void a_long_snippet_is_one_capped_line() throws Exception {
        String word = word();
        note("zz-h6-long-" + UUID.randomUUID(), word + " " + "uzun ".repeat(90));

        JsonNode hit = json(http().get("/api/v1/search?q=" + q(word))).get("hits").get(0);

        String snippet = hit.get("snippet").asString();
        assertThat(snippet).hasSizeLessThanOrEqualTo(301).endsWith("…").doesNotContain("\n");
    }

    private WriteResult note(String id, String text) {
        return writes.write(
                new WriteRequest(
                        Source.USER,
                        id,
                        "topic",
                        List.of(new FactInput("note", text, null, null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));
    }

    /** A word no other record holds, so a search for it finds only this test's records. */
    private static String word() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "zzh6" + HexFormat.of().formatHex(bytes);
    }

    private ApiHttp http() {
        return new ApiHttp(port);
    }

    private static JsonNode json(HttpResponse<String> reply) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }
}
