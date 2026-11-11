package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.api.ApiHttp;
import io.github.ismetdyn.agentkb.server.api.MaintenanceHttp;
import io.github.ismetdyn.agentkb.server.api.RestReadProbe;
import io.github.ismetdyn.agentkb.server.hooks.HookHttp;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invariant 2 for a value written as plain text first and stored on a Secret later (SecretLeakIT
 * covers the values stored first). A write refuses a stored value only once it is stored, so the
 * text is in the graph: as a fact, a summary, an alias (normalized), a waiting conflict and a
 * proposal. Every read answer, REST, MCP and hooks, must mask it. The snapshot keeps it as the user
 * wrote it (invariant 8), so it is not searched for there.
 */
class LateSecretLeakIT extends RunningServerTestBase {

    // Mixed case, so the alias (kept normalized) holds a form the raw value does not match. The
    // marker is in both forms.
    private static final String MARKER = "3c5e7a9b1d2f4608";
    private static final String LATE = "KbTest-Late-" + MARKER;
    private static final String ENTITY = "zz-late-leak";
    private static final String PROJECT = "zz-late-leak-proj";
    private static final String PROD = "prod";
    // F: entities whose ids hold the value in its normalized form, found by their hostnames.
    private static final String NORMALIZED = "kbtest-late-" + MARKER;
    private static final String HOST_ONE = "zz-late-one.ornek.example";
    private static final String HOST_TWIN = "zz-late-twin.ornek.example";
    private static final String SCANNED = NORMALIZED + "-scan";
    // F5: 6 characters with digits, so it counts anywhere, glued into a word too.
    private static final String GLUED = "Gl7u9q";
    private static final String GLUED_ENTITY = "zz-late-glued";
    private static final Map<String, String> DOC_SOURCE =
            Map.of("kind", "doc", "ref", "docs/runbook.md");
    private static final JsonMapper JSON = JsonMapper.shared();
    // Reads that show the records, so each must hold the mask; an empty answer passes the search.
    private static final List<String> SHOWING =
            List.of(
                    "GET entity json " + ENTITY,
                    "GET entity markdown " + ENTITY,
                    "GET entity markdown " + PROJECT,
                    "GET /api/v1/search?q=" + MARKER,
                    "GET /api/v1/conflicts?limit=500",
                    "GET /api/v1/proposals?limit=500",
                    "GET /api/v1/entities?limit=500",
                    "kb_read " + ENTITY,
                    "kb_read " + LATE,
                    "kb_search " + ENTITY,
                    "kb_stale",
                    "kb_brief " + PROJECT,
                    "kb_cypher " + ENTITY,
                    "hook user-prompt-submit claude",
                    "hook user-prompt-submit codex",
                    "kb_remember " + HOST_ONE,
                    "kb_remember " + HOST_TWIN,
                    "kb_scan again",
                    "GET check");

    @Test
    void a_value_stored_after_it_was_written_shows_in_no_read() throws Exception {
        writeTheValue();
        HttpResponse<String> put = new SecretHttp(port).put(ENTITY + "/prod/late-token", LATE);
        // E: the value is already in records, so the answer says to rotate it, never the value.
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(put.body()).contains("already in", "rotate").doesNotContain(MARKER);

        Map<String, String> reads = new LinkedHashMap<>(RestReadProbe.answers(port, LATE, ENTITY));
        reads.putAll(restReads());
        reads.putAll(mcpReads());
        reads.putAll(hookReads());
        reads.putAll(writeAnswers());

        for (String read : SHOWING) {
            assertThat(reads.get(read)).as(read).contains(WriteService.MASK);
        }
        assertThat(
                        reads.entrySet().stream()
                                .filter(read -> read.getValue().contains(MARKER))
                                .map(Map.Entry::getKey))
                .as("reads holding a value stored after it was written")
                .isEmpty();
    }

    /**
     * F5: PUT counts a copy glued into other text by the rule of reads and refusals, and says that
     * documents are not checked.
     */
    @Test
    void put_counts_a_glued_copy_and_says_documents_are_not_checked() throws Exception {
        Map<String, String> note =
                Map.of("key", "note", "value", "glued" + GLUED + "copy", "env", PROD);
        created(
                new ApiHttp(port),
                Map.of("id", GLUED_ENTITY, "type", "server", "facts", List.of(note)));

        HttpResponse<String> put = new SecretHttp(port).put(GLUED_ENTITY + "/prod/glued", GLUED);

        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(put.body())
                .contains("already in 1 records", "documents are not checked")
                .doesNotContain(GLUED);
    }

    /** A fact, summary and alias of ENTITY, a conflict on its os, a proposal and a project. */
    private void writeTheValue() throws IOException, InterruptedException {
        ApiHttp api = new ApiHttp(port);
        List<Map<String, String>> facts =
                List.of(
                        Map.of("key", "os", "value", "rhel 9", "env", PROD),
                        Map.of("key", "note", "value", "login " + LATE, "env", PROD));
        created(api, Map.of("id", ENTITY, "type", "server", "facts", facts));
        api.patch("/api/v1/entities/" + ENTITY, json(Map.of("aliases", List.of(LATE))));
        api.patch("/api/v1/entities/" + ENTITY, json(Map.of("summary", "box of " + LATE)));
        McpClient mcp = McpClient.open(port);
        // A doc source does not override the user's os: it waits as a conflict.
        Map<String, String> os = Map.of("key", "os", "value", "late " + LATE, "env", PROD);
        mcp.text(
                "kb_remember",
                Map.of("entity", ENTITY, "facts", List.of(os), "source", DOC_SOURCE));
        mcp.text("kb_propose", Map.of("kind", "rule-change", "content", "kbtest " + LATE));
        Map<String, String> dependsOn = Map.of("type", "depends_on", "target", ENTITY);
        created(api, Map.of("id", PROJECT, "type", "project", "relations", List.of(dependsOn)));
        api.patch("/api/v1/entities/" + PROJECT, json(Map.of("summary", "project of " + LATE)));
        for (Map.Entry<String, String> box :
                Map.of("-one", HOST_ONE, "-twin1", HOST_TWIN, "-twin2", HOST_TWIN).entrySet()) {
            Map<String, String> hostname = Map.of("key", "hostname", "value", box.getValue());
            Map<String, String> userOs = Map.of("key", "os", "value", "rhel 9", "env", PROD);
            created(
                    api,
                    Map.of(
                            "id",
                            NORMALIZED + box.getKey(),
                            "type",
                            "server",
                            "facts",
                            List.of(hostname, userOs)));
        }
        Path scanned =
                ScanProjects.project(scanRoot(), SCANNED, Map.of(".env", "LOG_LEVEL=debug\n"));
        mcp.text("kb_scan", Map.of("path", scanned.toString()));
    }

    /**
     * F: answers of writes and checks that name records by id: a write that found its entity by
     * hostname (its os then waits as a conflict, which kb check names), a write two entities match,
     * a rescan and kb check.
     */
    private Map<String, String> writeAnswers() throws IOException, InterruptedException {
        McpClient mcp = McpClient.open(port);
        Map<String, String> answers = new LinkedHashMap<>();
        for (String host : List.of(HOST_ONE, HOST_TWIN)) {
            Map<String, String> note = Map.of("key", "os", "value", "rhel 8", "env", PROD);
            answers.put(
                    "kb_remember " + host,
                    mcp.text(
                            "kb_remember",
                            Map.of("entity", host, "facts", List.of(note), "source", DOC_SOURCE)));
        }
        Path scanned = scanRoot().resolve(SCANNED);
        answers.put("kb_scan again", mcp.text("kb_scan", Map.of("path", scanned.toString())));
        committedRepo();
        answers.put("GET check", new MaintenanceHttp(port).get(MaintenanceHttp.CHECK).body());
        return answers;
    }

    private Map<String, String> restReads() throws IOException, InterruptedException {
        ApiHttp api = new ApiHttp(port);
        Map<String, String> reads = new LinkedHashMap<>();
        for (String path :
                List.of(
                        "/api/v1/search?q=" + MARKER,
                        "/api/v1/search?q=" + ENTITY,
                        "/api/v1/entities/" + PROJECT)) {
            reads.put("GET " + path, api.get(path).body());
        }
        reads.put(
                "GET entity markdown " + PROJECT,
                api.get("/api/v1/entities/" + PROJECT, ApiHttp.MARKDOWN).body());
        return reads;
    }

    private Map<String, String> mcpReads() throws IOException, InterruptedException {
        McpClient mcp = McpClient.open(port);
        Map<String, String> reads = new LinkedHashMap<>();
        for (String id : List.of(ENTITY, LATE, PROJECT)) {
            reads.put("kb_read " + id, mcp.text("kb_read", Map.of("id", id)));
        }
        for (String query : List.of(MARKER, ENTITY)) {
            reads.put("kb_search " + query, mcp.text("kb_search", Map.of("query", query)));
        }
        reads.put(
                "kb_related " + PROJECT,
                mcp.text("kb_related", Map.of("id", PROJECT, "direction", "both", "depth", 2)));
        reads.put("kb_stale", mcp.text("kb_stale", Map.of("days", 1)));
        reads.put("kb_brief " + PROJECT, mcp.text("kb_brief", Map.of("project", PROJECT)));
        String cypher = "MATCH (e:Entity {id: '" + ENTITY + "'}) RETURN e";
        reads.put("kb_cypher " + ENTITY, mcp.text("kb_cypher", Map.of("query", cypher)));
        return reads;
    }

    /** UserPromptSubmit recall; SessionStart gives the brief kb_brief gives. */
    private Map<String, String> hookReads() throws IOException, InterruptedException {
        HookHttp hooks = new HookHttp(port);
        Map<String, String> reads = new LinkedHashMap<>();
        for (String client : HookHttp.CLIENTS) {
            reads.put(
                    "hook user-prompt-submit " + client,
                    HookHttp.context(
                            HookHttp.USER_PROMPT_SUBMIT,
                            hooks.post(
                                    HookHttp.USER_PROMPT_SUBMIT,
                                    client,
                                    HookHttp.userPromptSubmit(client, ENTITY + " notu"))));
        }
        return reads;
    }

    private static void created(ApiHttp api, Map<String, ?> entity)
            throws IOException, InterruptedException {
        HttpResponse<String> reply = api.post("/api/v1/entities", json(entity));
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
    }

    private static String json(Map<String, ?> body) {
        return JSON.writeValueAsString(body);
    }
}
