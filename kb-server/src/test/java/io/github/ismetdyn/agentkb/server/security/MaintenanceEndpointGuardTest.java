package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0020: revert, restore and check sit behind OriginGuard like every path. A web page (any
 * Origin, or a foreign Host after DNS rebinding) neither reverts a batch nor restores the graph nor
 * reads the check report. OriginGuardTest covers the status on every path; this one covers the
 * effect.
 */
class MaintenanceEndpointGuardTest extends SecurityTestBase {

    private static final String ORIGIN = "Origin: http://attacker.example";

    @Autowired private WriteService writes;
    @Autowired private EmbeddedNeo4jStore store;

    @Test
    void a_browser_request_does_not_revert_a_batch() throws Exception {
        String batch = "kbtest-guard-" + UUID.randomUUID();
        String entity = "kbtest-guard-revert-" + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        entity,
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        batch,
                        Instant.now()));
        String body = "{\"batch\": \"" + batch + "\"}";

        Reply withOrigin = postJson("/api/v1/revert", body, host(), ORIGIN);
        Reply forgedHost = postJson("/api/v1/revert", body, "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(count("MATCH (e:Entity {id: $id}) RETURN count(e) AS n", Map.of("id", entity)))
                .isOne();
    }

    @Test
    void a_browser_request_does_not_restore() throws Exception {
        String entity = "kbtest-guard-restore-" + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        entity,
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));

        Reply withOrigin = postJson("/api/v1/restore", "{}", host(), ORIGIN);
        Reply forgedHost = postJson("/api/v1/restore", "{}", "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(count("MATCH (e:Entity {id: $id}) RETURN count(e) AS n", Map.of("id", entity)))
                .isOne();
    }

    /**
     * The graph differs from HEAD (a new record), so a sync that got through would commit. Passes
     * before G13 too (the guard answers every path); SecretLeakIT proves the endpoint syncs.
     */
    @Test
    void a_browser_request_does_not_sync() throws Exception {
        Path repo = committedRepo();
        String head = ScanProjects.git(repo, "rev-parse", "HEAD").strip();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        "zz-guard-sync-" + UUID.randomUUID(),
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        null,
                        Instant.now()));

        Reply withOrigin = postJson("/api/v1/sync", "{}", host(), ORIGIN);
        Reply forgedHost = postJson("/api/v1/sync", "{}", "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(ScanProjects.git(repo, "rev-parse", "HEAD").strip()).isEqualTo(head);
    }

    @Test
    void a_browser_request_does_not_read_the_check_report() throws Exception {
        Reply withOrigin = RawHttp.send(port, HTTP_1_1, "GET", "/api/v1/check", "", host(), ORIGIN);
        Reply forgedHost =
                RawHttp.send(
                        port,
                        HTTP_1_1,
                        "GET",
                        "/api/v1/check",
                        "",
                        "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(withOrigin.body() + forgedHost.body()).doesNotContain("problems");
    }

    private long count(String cypher, Map<String, Object> params) {
        return store.read(tx -> (Long) tx.execute(cypher, params).next().get("n"));
    }
}
