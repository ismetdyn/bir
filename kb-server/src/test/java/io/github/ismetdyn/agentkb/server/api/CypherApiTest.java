package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/cypher}: CypherGuard first, then kb_cypher's row and character limits, cells
 * as text; a refusal is {@code {error, hint}} without the query.
 */
class CypherApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired private KnowledgeQueries queries;

    @Test
    void cypher_returns_columns_and_rows_as_text() throws Exception {
        JsonNode answer = cypher("RETURN 1 AS one, [2, 3] AS list, null AS none");

        assertThat(answer.get("columns").toString()).isEqualTo("[\"one\",\"list\",\"none\"]");
        assertThat(answer.get("rows").get(0).get("cells").toString())
                .isEqualTo("[\"1\",\"[2, 3]\",\"\"]");
        assertThat(answer.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void cypher_cuts_at_200_rows_like_kb_cypher() throws Exception {
        JsonNode answer = cypher("UNWIND range(1, 500) AS i RETURN i");

        assertThat(answer.get("rows")).hasSize(KnowledgeQueries.CYPHER_ROWS);
        assertThat(answer.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void cypher_cuts_a_wide_answer_at_the_kb_cypher_budget() throws Exception {
        JsonNode answer =
                cypher(
                        "UNWIND range(1, 200) AS i RETURN i,"
                                + " reduce(s = '', x IN range(1, 100) | s + 'x') AS pad");

        assertThat(answer.get("rows").size()).isLessThan(200).isPositive();
        assertThat(answer.get("truncated").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE (n:Entity {id: 'zz-h6-cypher-write'}) RETURN n",
                "MATCH (s:Secret) RETURN s.secret_value",
                "LOAD CSV FROM 'file:///etc/passwd' AS l RETURN l"
            })
    void cypher_refuses_what_cypher_guard_refuses(String query) throws Exception {
        HttpResponse<String> reply = post(query);

        assertRefused(reply, 400, query);
        assertThat(queries.entity("zz-h6-cypher-write")).isEmpty();
    }

    /**
     * Lead H6 review 1: a query CypherGuard lets through but that fails or times out is the user's.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "RETURN 1/0 AS zzh6div",
                "UNWIND range(1, 100000) AS a UNWIND range(1, 100000) AS b"
                        + " RETURN count(*) AS zzh6cartesian"
            })
    void cypher_that_fails_at_run_time_is_refused_without_the_query(String query) throws Exception {
        assertRefused(post(query), 400, query, "zzh6");
    }

    private JsonNode cypher(String query) throws Exception {
        HttpResponse<String> reply = post(query);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return JSON.readTree(reply.body());
    }

    private HttpResponse<String> post(String query) throws Exception {
        return new ApiHttp(port)
                .post("/api/v1/cypher", JSON.writeValueAsString(Map.of("query", query)));
    }
}
