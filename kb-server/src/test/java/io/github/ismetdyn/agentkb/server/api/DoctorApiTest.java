package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/doctor} (docs/CLIENTS.md §1–§2, plan.md H7) on the test server: the five
 * checks in order, each with name, ok and hint; the data directory the store made is owner-only and
 * the committed repo is a repository. The answer names no configured path.
 */
class DoctorApiTest extends RunningServerTestBase {

    @Value("${agentkb.data-dir}")
    private String dataDir;

    @Value("${agentkb.repo}")
    private String repo;

    @Test
    void doctor_lists_its_checks_and_the_test_setup_passes_them() throws Exception {
        committedRepo();

        HttpResponse<String> reply = new MaintenanceHttp(port).get("/api/v1/doctor");

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode body = DocumentHttp.json(reply);
        Map<String, Boolean> checks = new LinkedHashMap<>();
        body.get("checks")
                .forEach(
                        check -> {
                            assertThat(check.has("hint")).isTrue();
                            checks.put(check.get("name").asString(), check.get("ok").asBoolean());
                        });
        assertThat(checks)
                .containsOnlyKeys("git", "git-lfs", "repo", "lfs-filter", "data-dir")
                .containsEntry("git", true)
                .containsEntry("repo", true)
                .containsEntry("data-dir", true);
        assertThat(body.get("ok").asBoolean()).isEqualTo(!checks.containsValue(false));
        assertThat(reply.body()).doesNotContain(dataDir).doesNotContain(repo);
    }
}
