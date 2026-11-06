package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ImportedMemoriesApiTest.IMPORT;
import static io.github.ismetdyn.agentkb.server.api.ImportedMemoriesApiTest.body;
import static io.github.ismetdyn.agentkb.server.api.ImportedMemoriesApiTest.memory;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0017 decision 1 for {@code kb import-memories}: long text belongs in the document store, so
 * the text of an imported memory is at most the body limit, 4,000 characters. Import is lossless
 * about what it receives (invariant 11's spirit for raw input): a longer memory is cut, with a
 * marker, never refused, and the answer reports how many were cut.
 *
 * <p>Contract fixed by this test: the answer gains {@code "truncated": <number of memories cut>}
 * next to {@code received} and {@code opened}; the stored text (the payload after its {@code source
 * path} line) is at most {@link #BODY_LIMIT} characters, marker included. The marker's wording is
 * not fixed here.
 */
class ImportedMemoryLimitTest extends RunningServerTestBase {

    // ADR-0017 decision 1: body 4,000 characters.
    static final int BODY_LIMIT = 4_000;
    private static final String PAYLOADS =
            "MATCH (s:Signal {kind: 'imported-memory'}) WHERE s.payload CONTAINS $text"
                    + " RETURN s.payload AS payload";
    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void a_memory_longer_than_the_body_limit_is_cut_with_a_marker_and_reported() throws Exception {
        String tag = "zz-g14-cut-" + UUID.randomUUID();
        String tail = "zz-g14-tail-" + UUID.randomUUID();
        String text = filled(tag + "\n", 50_000) + tail;

        HttpResponse<String> reply = post(body(memory("claude-code", ".claude/long.md", text)));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode answer = JSON.readTree(reply.body());
        assertThat(answer.path("received").asInt(-1)).isEqualTo(1);
        assertThat(answer.path("opened").asInt(-1)).isEqualTo(1);
        assertThat(answer.path("truncated").asInt(-1)).as(reply.body()).isEqualTo(1);
        String stored = storedText(tag);
        assertThat(stored.length()).isLessThanOrEqualTo(BODY_LIMIT);
        assertThat(stored).as("the head of the memory stays").startsWith(text.substring(0, 3_000));
        assertThat(stored).doesNotContain(tail);
        assertThat(text).as("a marker, not a silent prefix").doesNotStartWith(stored);
    }

    /** The boundary: exactly the limit is kept whole and not reported. */
    @Test
    void a_memory_of_exactly_the_body_limit_is_kept_whole() throws Exception {
        String tag = "zz-g14-exact-" + UUID.randomUUID();
        String text = filled(tag + "\n", BODY_LIMIT);

        HttpResponse<String> reply = post(body(memory("codex", ".codex/memories/x.md", text)));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(JSON.readTree(reply.body()).path("truncated").asInt(-1))
                .as(reply.body())
                .isZero();
        assertThat(storedText(tag)).isEqualTo(text);
    }

    /** SPEC §15: the cut memory imported again opens no second signal. */
    @Test
    void the_same_long_memory_imported_twice_is_one_signal() throws Exception {
        String tag = "zz-g14-cut-twice-" + UUID.randomUUID();
        String body =
                body(memory("claude-code", ".claude/long.md", filled(tag + "\n", BODY_LIMIT * 3)));

        post(body);
        HttpResponse<String> second = post(body);

        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(JSON.readTree(second.body()).path("opened").asInt(-1)).isZero();
        assertThat(payloads(tag)).hasSize(1);
    }

    /**
     * The cut falls inside a secret's value: one password line runs from character 3,500 to past
     * 4,300, so wherever a marker of any sensible length puts the cut, it splits the value. No part
     * of the value may reach the graph or the answer (invariant 2): mask first, then cut.
     */
    @Test
    void a_secret_split_by_the_cut_is_still_masked() throws Exception {
        String tag = "zz-g14-cut-secret-" + UUID.randomUUID();
        String value = "kbtest-" + "Hunter77".repeat(100);
        String text =
                filled(tag + "\n", 3_500)
                        + "the db password: "
                        + value
                        + "\n"
                        + filled("", BODY_LIMIT);

        HttpResponse<String> reply = post(body(memory("claude-code", ".claude/x.md", text)));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(payloads(tag))
                .singleElement()
                .satisfies(
                        payload -> {
                            assertThat(payload).contains(tag);
                            assertThat(payload).doesNotContain("kbtest-Hu", "Hunter77");
                        });
        assertThat(reply.body()).doesNotContain("kbtest-Hu");
    }

    /** {@code start} followed by plain words up to exactly {@code length} characters. */
    private static String filled(String start, int length) {
        StringBuilder out = new StringBuilder(start);
        while (out.length() < length) {
            out.append("deploy notes line ").append(out.length()).append('\n');
        }
        return out.substring(0, length);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return new MaintenanceHttp(port).post(IMPORT, body);
    }

    /** The memory text of the one signal holding {@code tag}: its payload after the path line. */
    private String storedText(String tag) {
        String payload = payloads(tag).getFirst();
        assertThat(payloads(tag)).hasSize(1);
        return payload.substring(payload.indexOf('\n') + 1);
    }

    private List<String> payloads(String text) {
        return store.read(
                tx ->
                        tx.execute(PAYLOADS, Map.of("text", text)).stream()
                                .map(row -> (String) row.get("payload"))
                                .toList());
    }
}
