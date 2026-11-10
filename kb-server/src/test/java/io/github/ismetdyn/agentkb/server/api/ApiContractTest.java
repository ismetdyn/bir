package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.CHECK;
import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.RESTORE;
import static io.github.ismetdyn.agentkb.server.api.MaintenanceHttp.REVERT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server half of the kb-api contract (ADR-0025 decision 6, plan.md H2). The files under
 * fixtures/api/ are the contract: here the server writes each kb-api record as exactly its file,
 * and every F2 JSON endpoint answers with the fields of its kb-api record, no more and no fewer.
 * The client half (kb-client ApiContractTest) reads the same files into equal records with
 * Json.java; a sample that drifts on one side fails against the file.
 *
 * <p>Not called here: {@code POST /api/v1/sync}. It commits the shared context's repository, whose
 * HEAD restoreSeed() restores, so it would leak test records into other tests; its record is
 * covered by the file test and SecretLeakIT's sync.
 */
class ApiContractTest extends RunningServerTestBase {

    static final Path CONTRACT = Path.of("..", "fixtures", "api");

    @Autowired private JsonMapper json;
    @Autowired private WriteService writes;

    /** Each contract file with the record it stands for; the client test lists the same pairs. */
    static Stream<Arguments> samples() {
        return Stream.of(
                arguments(
                        "revert-answer.json",
                        new RevertAnswer("01KBTESTBATCH0000000000000Z", 7, 2, 1)),
                arguments("restore-answer.json", new RestoreAnswer(140, 212, 3, 5, 1, 4)),
                arguments(
                        "sync-answer.json",
                        new SyncAnswer(
                                "3f9a1c0e5b7d2468ace013579bdf2468ace01357", true, 140, 212, 2)),
                arguments("sync-answer-no-commit.json", new SyncAnswer(null, false, 141, 213, 0)),
                arguments(
                        "check-answer.json",
                        new CheckAnswer(
                                false,
                                List.of(
                                        new CheckAnswer.Problem(
                                                "orphan-edge",
                                                "01KBTESTEDGE",
                                                "points to missing node kbtest-ğhost-İ \"quoted\""
                                                        + " \\ /"),
                                        new CheckAnswer.Problem(
                                                "git-fsck",
                                                "repo",
                                                "error: object file is empty")))),
                arguments("check-answer-clean.json", new CheckAnswer(true, List.of())),
                arguments("memory-import-answer.json", new MemoryImportAnswer(4, 3, 1)),
                arguments("search-answer.json", H6Samples.SEARCH),
                arguments("search-answer-empty.json", new SearchAnswer(List.of(), 0)),
                arguments("entity-answer.json", H6Samples.ENTITY),
                arguments("entity-answer-bare.json", H6Samples.BARE_ENTITY),
                arguments("write-answer.json", H6Samples.WRITE),
                arguments("edit-answer.json", new EditAnswer(H6Samples.BATCH)),
                arguments("cypher-answer.json", H6Samples.CYPHER),
                arguments("conflicts-answer.json", H6Samples.CONFLICTS),
                arguments("proposals-answer.json", H6Samples.PROPOSALS),
                arguments("activity-answer.json", H6Samples.ACTIVITY),
                arguments("secrets-answer.json", H6Samples.SECRETS),
                arguments("scan-answer.json", H6Samples.SCAN));
    }

    @ParameterizedTest
    @MethodSource("samples")
    void the_server_writes_each_record_as_its_contract_file(String file, Record sample)
            throws IOException {
        JsonNode written = json.valueToTree(sample);

        assertThat(written).isEqualTo(json.readTree(contract(file)));
    }

    @Test
    void check_answers_with_the_fields_of_check_answer() throws Exception {
        assertWireIs(new MaintenanceHttp(port).get(CHECK), CheckAnswer.class);
    }

    @Test
    void restore_answers_with_the_fields_of_restore_answer() throws Exception {
        committedRepo();
        assertWireIs(new MaintenanceHttp(port).post(RESTORE, "{}"), RestoreAnswer.class);
    }

    @Test
    void revert_answers_with_the_fields_of_revert_answer() throws Exception {
        String batch = "kbtest-contract-" + UUID.randomUUID();
        writes.write(
                new WriteRequest(
                        Source.USER,
                        "kbtest-contract-" + UUID.randomUUID(),
                        "server",
                        List.of(new FactInput("os", "rhel 9", "prod", null)),
                        List.of(),
                        null,
                        batch,
                        Instant.now()));

        assertWireIs(
                new MaintenanceHttp(port).post(REVERT, "{\"batch\": \"" + batch + "\"}"),
                RevertAnswer.class);
    }

    @Test
    void imported_memories_answers_with_the_fields_of_memory_import_answer() throws Exception {
        String memory =
                "{\"memories\": [{\"source\": \"codex\", \"path\": \".codex/memories/kbtest.md\","
                        + " \"text\": \"kbtest contract "
                        + UUID.randomUUID()
                        + "\"}]}";

        assertWireIs(
                new MaintenanceHttp(port).post("/api/v1/signals/imported-memories", memory),
                MemoryImportAnswer.class);
    }

    /**
     * The answer read into {@code type} and written back is the answer itself: a field the record
     * lacks, or a record field the answer lacks, makes the two trees differ.
     */
    private void assertWireIs(HttpResponse<String> reply, Class<? extends Record> type) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode wire = json.readTree(reply.body());

        JsonNode roundTrip = json.valueToTree(json.readValue(reply.body(), type));

        assertThat(roundTrip).isEqualTo(wire);
    }

    private static String contract(String file) throws IOException {
        return Files.readString(CONTRACT.resolve(file), StandardCharsets.UTF_8);
    }
}
