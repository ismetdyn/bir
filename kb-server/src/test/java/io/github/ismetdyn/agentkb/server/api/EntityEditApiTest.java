package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The user's edits of existing records over REST (docs/CLIENTS.md §1, ADR-0025 decisions 10, 15,
 * 18): PATCH aliases and summary within the boundary limits, close a fact or relation by its id,
 * archive and unarchive. Each is one batch; a refusal is 400 {@code {error, hint}}.
 */
class EntityEditApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void patch_sets_aliases_and_summary() throws Exception {
        String id = StoredRecords.server(writes);
        List<String> twenty =
                IntStream.range(0, 20).mapToObj(i -> "zz-h6-alias-" + i + "-" + id).toList();

        HttpResponse<String> reply =
                http().patch(
                                "/api/v1/entities/" + id,
                                body(Map.of("aliases", twenty, "summary", "edited")));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        Map<String, Object> node =
                StoredRecords.node(store, "MATCH (e:Entity {id: $id}) RETURN e", id);
        assertThat(node).containsEntry("summary", "edited");
        assertThat((String[]) node.get("aliases")).hasSize(20);
    }

    @Test
    void patch_refuses_more_than_20_aliases_or_one_over_the_length_limit() throws Exception {
        String id = StoredRecords.server(writes);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add("zz-h6-many-" + i);
        }
        String longAlias = "zz-h6-" + "a".repeat(301);

        assertRefused(
                http().patch("/api/v1/entities/" + id, body(Map.of("aliases", many))),
                400,
                "zz-h6-many-0");
        assertRefused(
                http().patch("/api/v1/entities/" + id, body(Map.of("aliases", List.of(longAlias)))),
                400,
                longAlias);
        assertThat(StoredRecords.node(store, "MATCH (e:Entity {id: $id}) RETURN e", id))
                .doesNotContainKey("aliases");
    }

    @Test
    void close_fact_closes_it_and_refuses_an_unknown_one() throws Exception {
        String id = StoredRecords.server(writes);
        String fact =
                StoredRecords.rows(
                                store,
                                "MATCH (:Entity {id: $id})-[:HAS_FACT]->(f:Fact) RETURN f",
                                Map.of("id", id))
                        .getFirst()
                        .get("id")
                        .toString();

        HttpResponse<String> reply = http().post("/api/v1/facts/" + fact + "/close", "{}");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        String batch = JSON.readTree(reply.body()).get("batch").asString();
        assertThat(StoredRecords.node(store, "MATCH (f:Fact {id: $id}) RETURN f", fact))
                .containsKey("valid_to")
                .containsEntry("closed_batch", batch);
        assertRefused(http().post("/api/v1/facts/" + fact + "/close", "{}"), 400);
    }

    @Test
    void relations_open_and_close_by_their_id() throws Exception {
        String target = StoredRecords.server(writes);
        String id = StoredRecords.server(writes);

        HttpResponse<String> opened =
                http().post(
                                "/api/v1/entities/" + id + "/relations",
                                body(
                                        Map.of(
                                                "relations",
                                                List.of(
                                                        Map.of(
                                                                "type", "behind", "target", target,
                                                                "env", "prod")))));

        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
        Map<String, Object> edge =
                StoredRecords.rows(
                                store,
                                "MATCH (:Entity {id: $id})-[r:BEHIND]->(:Entity {id: $target}) RETURN r",
                                Map.of("id", id, "target", target))
                        .getFirst();
        assertThat(edge).containsEntry("source", "user");
        String relation = edge.get("id").toString();
        HttpResponse<String> closed = http().post("/api/v1/relations/" + relation + "/close", "{}");
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(
                        StoredRecords.rows(
                                store,
                                "MATCH (:Entity {id: $id})-[r:BEHIND]->() WHERE r.valid_to IS NULL"
                                        + " RETURN r",
                                Map.of("id", id)))
                .isEmpty();
    }

    @Test
    void archive_and_unarchive_change_the_status_and_refuse_a_repeat() throws Exception {
        String id = StoredRecords.server(writes);

        HttpResponse<String> archived = http().post("/api/v1/entities/" + id + "/archive", "{}");

        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(StoredRecords.node(store, "MATCH (e:Entity {id: $id}) RETURN e", id))
                .containsEntry("status", "archived");
        assertRefused(http().post("/api/v1/entities/" + id + "/archive", "{}"), 400);
        assertThat(http().post("/api/v1/entities/" + id + "/unarchive", "{}").statusCode())
                .isEqualTo(200);
        assertThat(StoredRecords.node(store, "MATCH (e:Entity {id: $id}) RETURN e", id))
                .containsEntry("status", "current");
    }

    private static String body(Map<String, ?> fields) {
        return JSON.writeValueAsString(fields);
    }

    private ApiHttp http() {
        return new ApiHttp(port);
    }
}
