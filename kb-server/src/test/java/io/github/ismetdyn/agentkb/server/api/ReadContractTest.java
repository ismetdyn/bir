package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.WireShape.assertWireIs;

import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import org.junit.jupiter.api.Test;

/** The H6 read endpoints answer with the fields of their kb-api records (ApiContractTest). */
class ReadContractTest extends RunningServerTestBase {

    @Test
    void search_and_stale_answer_with_the_fields_of_search_answer() throws Exception {
        ApiHttp api = new ApiHttp(port);
        assertWireIs(api.get("/api/v1/search?q=ornek&limit=20"), SearchAnswer.class, 200);
        assertWireIs(api.get("/api/v1/stale?days=0"), SearchAnswer.class, 200);
    }

    @Test
    void entity_answers_with_the_fields_of_entity_answer() throws Exception {
        assertWireIs(
                new ApiHttp(port).get("/api/v1/entities/ornek-app-01"), EntityAnswer.class, 200);
    }

    @Test
    void cypher_answers_with_the_fields_of_cypher_answer() throws Exception {
        assertWireIs(
                new ApiHttp(port)
                        .post(
                                "/api/v1/cypher",
                                "{\"query\": \"MATCH (e:Entity) RETURN e.id AS id,"
                                        + " e.aliases AS aliases LIMIT 3\"}"),
                CypherAnswer.class,
                200);
    }
}
