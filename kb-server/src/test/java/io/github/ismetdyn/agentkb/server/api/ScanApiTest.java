package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/scan {path}} (invariant 9): a working copy under agentkb.scan.allowed-roots
 * is scanned; any other path is refused before anything is read, and the refusal does not repeat
 * it.
 */
class ScanApiTest extends RunningServerTestBase {

    private static final JsonMapper JSON = JsonMapper.shared();
    // Its own project, so the scan leaves the seed's records as they are for other classes.
    private static final String PROJECT = "zz-h6-scan";
    private static final String PROD_PASSWORD = "kbtest-h6-prod-pw-2";

    @Test
    void a_path_under_the_allowed_roots_is_scanned() throws Exception {
        Path project =
                ScanProjects.springProject(
                        Files.createDirectories(scanRoot().resolve("zz-h6-" + UUID.randomUUID())),
                        PROJECT,
                        PROD_PASSWORD,
                        "kbtest-h6-local-pw-2");

        HttpResponse<String> reply = scan(Map.of("path", project.toString()));

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode answer = JSON.readTree(reply.body());
        assertThat(answer.get("project").asString()).isEqualTo(PROJECT);
        assertThat(answer.get("secrets").toString()).contains("kb:secret/");
        assertThat(reply.body()).doesNotContain(PROD_PASSWORD);
    }

    @Test
    void a_path_outside_the_allowed_roots_is_refused_without_echo() throws Exception {
        Path outside =
                Files.createDirectories(
                        scanRoot().resolveSibling("zz-h6-outside-" + UUID.randomUUID()));
        ScanProjects.copyFixture(outside);

        assertRefused(scan(Map.of("path", outside.toString())), 400, outside.toString());
    }

    @Test
    void a_missing_or_relative_path_is_refused() throws Exception {
        assertRefused(scan(Map.of()), 400);
        assertRefused(scan(Map.of("path", "zz-h6/relative")), 400, "zz-h6/relative");
    }

    private HttpResponse<String> scan(Map<String, String> body) throws Exception {
        return new ApiHttp(port).post("/api/v1/scan", JSON.writeValueAsString(body));
    }
}
