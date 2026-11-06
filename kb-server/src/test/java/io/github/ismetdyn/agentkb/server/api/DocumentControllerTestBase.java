package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Completion of an upload, {@code POST /api/v1/documents} (plan.md H4, ADR-0025 decisions 2–5, SPEC
 * §6.7, ADR-0017): hash re-check, document secret scan, name/origin checks, blob {@code
 * blobs/xx/<sha>} written before the Document node, the node through WriteService (invariant 1).
 * Every content holds a random line, so the tests share the server context without meeting each
 * other's documents; the seed entities are only ABOUT targets.
 */
abstract class DocumentControllerTestBase extends UploadControllerTestBase {

    protected static final String ENTITY = "ornek-proje";
    protected static final String OTHER_ENTITY = "ornek-app-01";

    @Autowired protected EmbeddedNeo4jStore store;

    protected DocumentHttp documents;

    @BeforeEach
    void documentClient() {
        documents = new DocumentHttp(port);
    }

    /** A small text document no other test has. */
    protected static byte[] uniqueText() {
        return ("zz-h4 runbook\nrestart the service with systemctl\n" + UUID.randomUUID() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    protected Path blob(String sha) {
        return repo.resolve(DocumentHttp.blobPath(sha));
    }

    /** The staged files of {@code sha}, whatever suffix they carry. */
    protected List<Path> stagedFiles(String sha) throws IOException {
        return named(staging(), sha);
    }

    /** Files under blobs/ named after {@code sha} with any suffix (a left-over .tmp included). */
    protected List<Path> blobFiles(String sha) throws IOException {
        return named(repo.resolve("blobs").resolve(sha.substring(0, 2)), sha);
    }

    private static List<Path> named(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    /** Properties of every Document node holding {@code sha}. */
    protected List<Map<String, Object>> documentNodes(String sha) {
        return rows(
                        "MATCH (d:Document {sha256: $sha}) RETURN properties(d) AS p",
                        Map.of("sha", sha))
                .stream()
                .map(
                        row -> {
                            Map<String, Object> copy = new TreeMap<>();
                            ((Map<?, ?>) row.get("p"))
                                    .forEach((key, value) -> copy.put(String.valueOf(key), value));
                            return copy;
                        })
                .toList();
    }

    /**
     * Ids of the records {@code docId} has an {@code edge} (ABOUT or SUPERSEDES) to, one entry per
     * edge, sorted.
     */
    protected List<String> targets(String docId, String edge) {
        return rows(
                        "MATCH (:Document {id: $id})-[:"
                                + edge
                                + "]->(t) RETURN t.id AS id ORDER BY id",
                        Map.of("id", docId))
                .stream()
                .map(row -> (String) row.get("id"))
                .toList();
    }

    protected List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(tx -> tx.execute(cypher, params).stream().toList());
    }
}
