package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.api.DocumentHttp;
import io.github.ismetdyn.agentkb.server.api.MaintenanceHttp;
import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.hooks.HookHttp;
import io.github.ismetdyn.agentkb.server.mcp.McpClient;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.secrets.SecretHttp;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotExporter;
import io.github.ismetdyn.agentkb.server.snapshot.SnapshotFormat;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLAUDE.md invariant 2 and docs/SECRETS.md §1: a value set through {@code PUT
 * /api/v1/secrets/<id>} leaves this machine's graph only through {@code GET} on the same path. Each
 * other way out is exercised, then searched for the value: every MCP tool answer (an agent handing
 * the value to kb_remember included), REST error bodies, the snapshot export and the git history of
 * a commit of it, the application log, every file under the data directory except the store itself
 * (Neo4j's logs), Signal payloads and the three hook answers (brief, recall, and a question
 * answered with the value, whose qa-capture signal must hold it masked). One class, extended as new
 * ways out arrive; G12 adds the restore, check and revert answers, G13 the sync answer and the
 * whole history of the remote it pushed to. The values are fake.
 *
 * <p>The scanner (G10) is a second way in: fixtures/scanner/ornek-proje is copied under the allowed
 * root and scanned twice, so its two fake passwords are stored by kb_scan, not by PUT, and are
 * searched for in the same ways out, kb_scan's own report included.
 *
 * <p>H4 (ADR-0025 decisions 3 and 4) adds documents: one holding a fake secret is uploaded and
 * refused at completion, a clean one is refused for a secret name or origin and then added. The
 * refusal bodies, the staging directory, blobs/ and .gitattributes join the ways out.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretLeakIT extends RunningServerTestBase {

    // Lower case and hostname-shaped, so a write path that does not know it is a secret takes it.
    private static final String MARKER = "7f3a9c2e51d84b06";
    private static final String VALUE = "kbtest-fake-" + MARKER;
    private static final String NEW_MARKER = "0b1d2c3e4f5a6978";
    private static final String NEW_VALUE = "kbtest-fake-" + NEW_MARKER;
    private static final String SEED_ID = "ornek-lb/prod/panel-admin";
    private static final String NEW_ID = "ornek-app-01/prod/kbtest-leak-token";
    private static final String ENTITY = "zz-g8-leak";
    private static final String ORPHANED_ID = ENTITY + "/prod/kbtest-orphaned";
    private static final Path STORE = Path.of("graph", "data");
    private static final Map<String, String> DOC_SOURCE =
            Map.of("kind", "doc", "ref", "docs/runbook.md");
    private static final String QUESTION = "zz-g9-leak-q";
    private static final String SCANNED_PROD_ID = "ornek-proje/prod/spring.datasource.password";
    private static final String SCANNED_LOCAL_ID = "ornek-proje/local/ORACLE_PASSWORD";
    // H4 (ADR-0025 decisions 3 and 4): a fake secret in an uploaded document, its name and origin.
    private static final String DOC_MARKER = "5e6f7a8b9c0d1e2f";
    private static final String DOC_SECRET_LINE = "DB_PASSWORD=kbtestdoc" + DOC_MARKER;
    private static final String CLEAN_DOC_NAME = "zz-h4-clean.md";
    private static final byte[] CLEAN_DOC =
            "zz-h4 clean document\n".getBytes(StandardCharsets.UTF_8);
    private static final List<String> FORBIDDEN =
            List.of(
                    MARKER,
                    NEW_MARKER,
                    DOC_MARKER,
                    ScanProjects.PROD_PASSWORD,
                    ScanProjects.LOCAL_PASSWORD);

    @Autowired private EmbeddedNeo4jStore store;
    @Autowired private PromptRing promptRing;

    @Value("${agentkb.data-dir}")
    private Path dataDir;

    @TempDir Path gitRepo;
    @TempDir Path workingCopy;
    @TempDir Path remoteDir;

    @Test
    void the_value_leaves_through_get_only(CapturedOutput output) throws Exception {
        SecretHttp secrets = new SecretHttp(port);
        assertThat(secrets.put(SEED_ID, VALUE).statusCode()).as("PUT existing").isEqualTo(204);
        assertThat(secrets.put(NEW_ID, NEW_VALUE).statusCode()).as("PUT new").isEqualTo(204);
        assertThat(secrets.get(SEED_ID).body()).as("the value is really stored").isEqualTo(VALUE);

        Map<String, String> paths = new LinkedHashMap<>();
        paths.putAll(scannerAnswers(secrets));
        paths.putAll(mcpAnswers());
        paths.putAll(hookAnswers());
        paths.putAll(restErrorBodies(secrets));
        paths.putAll(documentAnswers());
        paths.putAll(snapshotAndGitHistory());
        // G12 (ADR-0022 decision 38): the Secret under the agent's entity is not in HEAD, so the
        // restore keeps it without its entity and check lists it as a local record. The unknown
        // commit and batch are the value itself, so an echoing error shows. GET still returns
        // the values afterwards: the restore really kept them.
        committedRepo();
        assertThat(secrets.put(ORPHANED_ID, VALUE).statusCode()).as("PUT orphaned").isEqualTo(204);
        paths.putAll(new MaintenanceHttp(port).answers(VALUE));
        assertThat(paths.get("check")).contains(ORPHANED_ID);
        assertThat(secrets.get(ORPHANED_ID).body()).as("kept by restore").isEqualTo(VALUE);
        assertThat(secrets.get(NEW_ID).body()).as("kept by restore").isEqualTo(NEW_VALUE);
        // G13 (SPEC §10, invariants 2 and 8): kb sync pushes to a bare remote; its answer and the
        // remote's whole history are ways out, and the values and signals are still here after.
        String signalsBefore = signals();
        paths.putAll(new MaintenanceHttp(port).syncToBareRemote(committedRepo(), remoteDir));
        assertThat(secrets.get(SEED_ID).body()).as("kept by sync").isEqualTo(VALUE);
        assertThat(secrets.get(NEW_ID).body()).as("kept by sync").isEqualTo(NEW_VALUE);
        assertThat(signals()).as("signals kept by sync").isEqualTo(signalsBefore);
        paths.put("signal payloads", signals());
        paths.putAll(filesUnder(dataDir, "file ", STORE));
        paths.putAll(filesUnder(committedRepo(), "repo ", Path.of(".git")));
        paths.put("application log", output.getAll());

        // Each path was really taken; an empty answer would pass the search below.
        assertThat(paths.get("kb_read ornek-app-01")).contains("kb:secret/" + NEW_ID);
        assertThat(paths.get("snapshot")).contains(SEED_ID).contains(NEW_ID);
        assertThat(paths.get("kb_scan")).contains("kb:secret/" + SCANNED_PROD_ID);
        assertThat(paths.get("snapshot")).contains(SCANNED_LOCAL_ID);
        assertThat(paths.get("git history")).contains(NEW_ID);
        assertThat(paths.get("remote git history")).contains(NEW_ID).contains(ORPHANED_ID);
        assertThat(paths.get("signal payloads")).contains(ENTITY).contains(QUESTION);
        assertThat(paths.get("hook session-start claude")).contains("ornek-proje");
        assertThat(paths.get("kb_brief ornek-proje")).contains("ornek-proje");
        assertThat(paths).containsKey("file graph/logs/debug.log");
        String cleanBlob = DocumentHttp.blobPath(DocumentHttp.sha256(CLEAN_DOC));
        assertThat(paths.get("snapshot")).contains(CLEAN_DOC_NAME);
        assertThat(paths.get("remote git history")).contains(cleanBlob);
        assertThat(paths).containsKey("repo " + cleanBlob);
        assertThat(
                        paths.entrySet().stream()
                                .filter(
                                        path ->
                                                FORBIDDEN.stream()
                                                        .anyMatch(path.getValue()::contains))
                                .map(Map.Entry::getKey))
                .as("ways out holding a secret value")
                .isEmpty();
    }

    /**
     * kb_scan of the fixture, twice (a rescan must not leak what the first scan stored), then the
     * stored values read back through GET only, and the agent handing one to kb_remember.
     */
    private Map<String, String> scannerAnswers(SecretHttp secrets)
            throws IOException, InterruptedException {
        Path project = ScanProjects.copyFixture(scanRoot());
        McpClient mcp = McpClient.open(port);
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("kb_scan", mcp.text("kb_scan", Map.of("path", project.toString())));
        answers.put("kb_scan again", mcp.text("kb_scan", Map.of("path", project.toString())));
        assertThat(secrets.get(SCANNED_PROD_ID).body())
                .as("the scanned value is really stored")
                .isEqualTo(ScanProjects.PROD_PASSWORD);
        assertThat(secrets.get(SCANNED_LOCAL_ID).body()).isEqualTo(ScanProjects.LOCAL_PASSWORD);
        for (String value : List.of(ScanProjects.PROD_PASSWORD, ScanProjects.LOCAL_PASSWORD)) {
            answers.put("kb_remember scanned " + value, remember(mcp, value, DOC_SOURCE));
            answers.put(
                    "kb_search scanned " + value, mcp.text("kb_search", Map.of("query", value)));
            answers.put(
                    "PUT malformed with scanned " + value,
                    secrets.put("kbtest-no-slashes", value).body());
        }
        for (String id : List.of(SCANNED_PROD_ID, SCANNED_LOCAL_ID, "ornek-proje", "ornek-db")) {
            answers.put("kb_read scanned " + id, mcp.text("kb_read", Map.of("id", id)));
        }
        answers.put(
                "kb_cypher scanned secrets",
                mcp.text(
                        "kb_cypher",
                        Map.of("query", "MATCH (p)-[:HAS_SECRET]->(s:Secret) RETURN p, s")));
        return answers;
    }

    private Map<String, String> mcpAnswers() throws IOException, InterruptedException {
        McpClient mcp = McpClient.open(port);
        Map<String, String> answers = new LinkedHashMap<>();
        // An agent that saw the value elsewhere: plainly, as a dispute and in a password= line.
        String words = ENTITY + " adı " + ENTITY + ".ornek.example";
        promptRing.record(words, Instant.now());
        remember(mcp, ENTITY + ".ornek.example", Map.of("kind", "user_stated", "evidence", words));
        answers.put("kb_remember raw value", remember(mcp, VALUE, DOC_SOURCE));
        answers.put("kb_remember password=", remember(mcp, "password=" + VALUE, DOC_SOURCE));
        remember(mcp, ENTITY + "-elsewhere.ornek.example", DOC_SOURCE);
        for (String query : List.of(VALUE, MARKER, NEW_VALUE, "panel-admin", "kbtest", ENTITY)) {
            answers.put("kb_search " + query, mcp.text("kb_search", Map.of("query", query)));
        }
        for (String id : List.of(SEED_ID, NEW_ID, "ornek-lb", "ornek-app-01", ENTITY)) {
            answers.put("kb_read " + id, mcp.text("kb_read", Map.of("id", id)));
            answers.put(
                    "kb_related " + id,
                    mcp.text("kb_related", Map.of("id", id, "direction", "both", "depth", 3)));
        }
        for (String query :
                List.of(
                        "MATCH (s:Secret) RETURN s",
                        "MATCH (e)-[:HAS_SECRET]->(s:Secret) RETURN e, s",
                        "MATCH (s:Secret) RETURN s.secret_value",
                        "MATCH (n:Signal) RETURN n",
                        "RETURN '" + VALUE + "' AS guess")) {
            answers.put("kb_cypher " + query, mcp.text("kb_cypher", Map.of("query", query)));
        }
        answers.put("kb_brief ornek-proje", mcp.text("kb_brief", Map.of("project", "ornek-proje")));
        answers.put(
                "kb_brief ornek-app-01", mcp.text("kb_brief", Map.of("project", "ornek-app-01")));
        answers.put("kb_schema", mcp.text("kb_schema", Map.of()));
        answers.put("kb_stale", mcp.text("kb_stale", Map.of("days", 1)));
        answers.put("tools/list", mcp.toolsList().toString());
        return answers;
    }

    /**
     * The brief of ornek-proje (its own secret, ornek-app-01's as a neighbour), recall of prompts
     * naming the value, and questions answered with it: a stored value that does not look like a
     * secret is masked by being known, not by its shape.
     */
    private Map<String, String> hookAnswers() throws IOException, InterruptedException {
        HookHttp hooks = new HookHttp(port);
        Path repo =
                HookHttp.gitWorkingCopy(
                        workingCopy, "git@git.ornek.example:ornek-grup/ornek-proje.git");
        Map<String, String> answers = new LinkedHashMap<>();
        for (String client : HookHttp.CLIENTS) {
            answers.put(
                    "hook session-start " + client,
                    hooks.post(HookHttp.SESSION_START, client, HookHttp.sessionStart(client, repo))
                            .body());
            for (String prompt :
                    List.of(
                            VALUE,
                            MARKER,
                            NEW_VALUE,
                            "panel-admin",
                            "kbtest",
                            SEED_ID,
                            ENTITY,
                            ScanProjects.PROD_PASSWORD,
                            "ornek-proje parolası " + ScanProjects.LOCAL_PASSWORD)) {
                answers.put(
                        "hook user-prompt-submit " + client + " " + prompt,
                        hooks.post(
                                        HookHttp.USER_PROMPT_SUBMIT,
                                        client,
                                        HookHttp.userPromptSubmit(client, prompt))
                                .body());
            }
            answers.put(
                    "hook post-tool-use " + client,
                    hooks.post(
                                    HookHttp.POST_TOOL_USE,
                                    client,
                                    HookHttp.question(
                                            client,
                                            QUESTION + " " + client + " panel parolası? " + VALUE,
                                            "şu: " + NEW_VALUE))
                            .body());
        }
        return answers;
    }

    /**
     * H4: a document holding a fake secret is uploaded completely and refused at completion; a
     * clean document whose name or origin holds it is refused too. A clean document is added, so
     * the sync below really carries blobs/ to the remote. The refused bytes must not reach staging,
     * blobs/, the graph, the log or any answer.
     */
    private Map<String, String> documentAnswers() throws IOException, InterruptedException {
        DocumentHttp documents = new DocumentHttp(port);
        Map<String, String> answers = new LinkedHashMap<>();
        byte[] secretText =
                ("zz-h4 leak\n" + ENTITY + "\n" + DOC_SECRET_LINE + "\n")
                        .getBytes(StandardCharsets.UTF_8);
        String secretSha = documents.upload(secretText);
        HttpResponse<String> refused = documents.complete(secretSha, "zz-h4-leak.md");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        answers.put("POST /documents with a secret", refused.body());
        String cleanSha = documents.upload(CLEAN_DOC);
        HttpResponse<String> secretName =
                documents.complete(cleanSha, "password=kbtestdoc" + DOC_MARKER);
        assertThat(secretName.statusCode()).as(secretName.body()).isEqualTo(400);
        answers.put("POST /documents with a secret name", secretName.body());
        HttpResponse<String> secretOrigin =
                documents.complete(
                        cleanSha,
                        CLEAN_DOC_NAME,
                        null,
                        "https://kbuser:kbtestdoc" + DOC_MARKER + "@wiki.ornek.example/x",
                        null);
        assertThat(secretOrigin.statusCode()).as(secretOrigin.body()).isEqualTo(400);
        answers.put("POST /documents with a secret origin", secretOrigin.body());
        HttpResponse<String> added = documents.complete(cleanSha, CLEAN_DOC_NAME);
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
        answers.put("POST /documents clean", added.body());
        return answers;
    }

    private static String remember(McpClient mcp, String hostname, Map<String, String> source)
            throws IOException, InterruptedException {
        return mcp.text(
                "kb_remember",
                Map.of(
                        "entity",
                        ENTITY,
                        "type",
                        "server",
                        "facts",
                        List.of(Map.of("key", "hostname", "value", hostname, "env", "prod")),
                        "source",
                        source));
    }

    private static Map<String, String> restErrorBodies(SecretHttp secrets)
            throws IOException, InterruptedException {
        String malformed = "kbtest-no-slashes";
        return Map.of(
                "GET unknown id", secrets.get("ornek-app-01/prod/kbtest-unknown").body(),
                "GET from a browser", secrets.getFromBrowser(SEED_ID).body(),
                "PUT malformed id", secrets.put(malformed, VALUE).body(),
                "PUT as JSON",
                        secrets.send(
                                        "PUT",
                                        malformed,
                                        "application/json",
                                        "{\"v\":\"" + VALUE + "\"}")
                                .body(),
                "PUT as form",
                        secrets.send(
                                        "PUT",
                                        malformed,
                                        "application/x-www-form-urlencoded",
                                        "v=" + VALUE)
                                .body(),
                "POST", secrets.send("POST", SEED_ID, "text/plain", VALUE).body(),
                "DELETE", secrets.send("DELETE", SEED_ID, "text/plain", VALUE).body());
    }

    /** The export as kb sync writes it (invariant 8), committed the way kb sync commits it. */
    private Map<String, String> snapshotAndGitHistory() throws IOException, InterruptedException {
        Path snapshot = gitRepo.resolve("snapshot");
        SnapshotFormat.write(snapshot, new SnapshotExporter(store).export());
        ScanProjects.git(gitRepo, "init", "--quiet");
        ScanProjects.git(gitRepo, "add", "--all");
        ScanProjects.git(gitRepo, "commit", "--quiet", "--message", "snapshot");
        return Map.of(
                "snapshot",
                Files.readString(snapshot.resolve(SnapshotFormat.NODES_FILE))
                        + Files.readString(snapshot.resolve(SnapshotFormat.EDGES_FILE)),
                "git history",
                ScanProjects.git(gitRepo, "log", "--patch", "--all"));
    }

    private String signals() {
        return store.read(
                tx ->
                        tx
                                .execute("MATCH (s:Signal) RETURN properties(s) AS p ORDER BY s.id")
                                .stream()
                                .map(row -> String.valueOf(row.get("p")))
                                .toList()
                                .toString());
    }

    /**
     * Every file under {@code root} but {@code excluded}, keyed {@code label + relative path}. The
     * store holds the value by design, so the data dir is searched without it; the repo working
     * tree (blobs/, .gitattributes, H4) without .git, whose history is searched as git log.
     */
    private static Map<String, String> filesUnder(Path root, String label, Path excluded)
            throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path file : all.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(file);
                if (!relative.startsWith(excluded)) {
                    // ISO-8859-1 maps each byte to one char, so binary files are searched too.
                    files.put(
                            label + relative,
                            new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                }
            }
        }
        return files;
    }
}
