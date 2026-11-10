package io.github.ismetdyn.agentkb.server.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every H6 endpoint as a way out for SecretLeakIT (invariant 2): the reads that name secrets, and
 * refused writes, look-ups and filters that carry {@code value} itself, so an endpoint that echoes
 * a request value shows. Keyed {@code METHOD path}.
 */
public final class RestReadProbe {

    private static final JsonMapper JSON = JsonMapper.shared();

    private RestReadProbe() {}

    public static Map<String, String> answers(int port, String value, String entity)
            throws IOException, InterruptedException {
        ApiHttp api = new ApiHttp(port);
        Map<String, String> answers = new LinkedHashMap<>();
        for (String path :
                List.of(
                        "/api/v1/secrets",
                        "/api/v1/secrets?prefix=ornek-lb/",
                        "/api/v1/secrets?prefix=" + ApiHttp.q(value),
                        "/api/v1/search?q=" + ApiHttp.q(value),
                        "/api/v1/search?q=panel-admin&limit=20",
                        "/api/v1/search?q=kbtest&limit=20",
                        "/api/v1/activity?limit=500",
                        "/api/v1/activity?batch=" + ApiHttp.q(value),
                        "/api/v1/conflicts?limit=500",
                        "/api/v1/proposals?limit=500",
                        "/api/v1/stale?days=0&limit=500",
                        "/api/v1/entities/" + ApiHttp.q(value))) {
            answers.put("GET " + path, api.get(path).body());
        }
        for (String id : List.of("ornek-lb", "ornek-app-01", "ornek-proje", entity)) {
            answers.put("GET entity json " + id, api.get("/api/v1/entities/" + id).body());
            answers.put(
                    "GET entity markdown " + id,
                    api.get("/api/v1/entities/" + id, ApiHttp.MARKDOWN).body());
        }
        for (String query :
                List.of(
                        "MATCH (s:Secret) RETURN s",
                        "MATCH (e)-[:HAS_SECRET]->(s:Secret) RETURN e, s",
                        "MATCH (s:Secret) RETURN s.secret_value",
                        "RETURN '" + value + "' AS guess",
                        "CREATE (n:Entity {id: '" + value + "'})")) {
            answers.put(
                    "POST /api/v1/cypher " + query,
                    post(api, "/api/v1/cypher", Map.of("query", query)));
        }
        String facts = "/api/v1/entities/" + entity + "/facts";
        answers.put(
                "POST " + facts,
                post(api, facts, Map.of("facts", List.of(Map.of("key", "note", "value", value)))));
        answers.put(
                "PATCH aliases",
                api.patch(
                                "/api/v1/entities/" + entity,
                                JSON.writeValueAsString(Map.of("aliases", List.of(value))))
                        .body());
        answers.put(
                "PATCH summary",
                api.patch(
                                "/api/v1/entities/" + entity,
                                JSON.writeValueAsString(Map.of("summary", value)))
                        .body());
        answers.put(
                "POST /api/v1/scan", post(api, "/api/v1/scan", Map.of("path", "/zz-h6/" + value)));
        answers.put(
                "POST resolve",
                post(
                        api,
                        "/api/v1/conflicts/" + value + "/resolve",
                        Map.of("resolution", "keep-old")));
        answers.put(
                "POST reject",
                post(api, "/api/v1/proposals/" + value + "/reject", Map.of("note", value)));
        return answers;
    }

    private static String post(ApiHttp api, String path, Map<String, ?> body)
            throws IOException, InterruptedException {
        return api.post(path, JSON.writeValueAsString(body)).body();
    }
}
