package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.WireShape.assertWireIs;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import io.github.ismetdyn.agentkb.server.scan.ScanProjects;
import io.github.ismetdyn.agentkb.server.write.ProposalRequest;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The H6 write, review and scan endpoints answer with the fields of their kb-api records
 * (ApiContractTest).
 */
class WriteContractTest extends RunningServerTestBase {

    @Autowired private WriteService writes;

    @Test
    void entity_writes_answer_with_the_fields_of_write_answer() throws Exception {
        ApiHttp api = new ApiHttp(port);
        String id = "zz-h6-contract-" + UUID.randomUUID();
        String target = StoredRecords.server(writes);
        assertWireIs(
                api.post(
                        "/api/v1/entities",
                        "{\"id\": \""
                                + id
                                + "\", \"type\": \"server\", \"facts\": [{\"key\": \"os\","
                                + " \"value\": \"rhel 8\"}]}"),
                WriteAnswer.class,
                201);
        assertWireIs(
                api.post(
                        "/api/v1/entities/" + id + "/facts",
                        "{\"facts\": [{\"key\": \"os\", \"value\": \"rhel 9\"}]}"),
                WriteAnswer.class,
                200);
        assertWireIs(
                api.post(
                        "/api/v1/entities/" + id + "/relations",
                        "{\"relations\": [{\"type\": \"behind\", \"target\": \""
                                + target
                                + "\"}]}"),
                WriteAnswer.class,
                200);
        assertWireIs(
                api.patch("/api/v1/entities/" + id, "{\"summary\": \"contract\"}"),
                EditAnswer.class,
                200);
        assertWireIs(api.post("/api/v1/entities/" + id + "/archive", "{}"), EditAnswer.class, 200);
    }

    @Test
    void review_lists_answer_with_the_fields_of_their_records() throws Exception {
        writes.propose(
                new ProposalRequest(
                        Source.AGENT, "rule-change", null, "zz-h6 contract rule", "contract"));
        ApiHttp api = new ApiHttp(port);
        assertWireIs(api.get("/api/v1/conflicts"), ConflictsAnswer.class, 200);
        assertWireIs(api.get("/api/v1/proposals"), ProposalsAnswer.class, 200);
        assertWireIs(api.get("/api/v1/activity"), ActivityAnswer.class, 200);
        assertWireIs(api.get("/api/v1/secrets"), SecretsAnswer.class, 200);
    }

    @Test
    void scan_answers_with_the_fields_of_scan_answer() throws Exception {
        Path project =
                ScanProjects.springProject(
                        Files.createDirectories(scanRoot().resolve("zz-h6-" + UUID.randomUUID())),
                        "zz-h6-contract-scan",
                        "kbtest-h6-prod-pw-1",
                        "kbtest-h6-local-pw-1");
        assertWireIs(
                new ApiHttp(port)
                        .post(
                                "/api/v1/scan",
                                JsonMapper.shared()
                                        .writeValueAsString(Map.of("path", project.toString()))),
                ScanAnswer.class,
                200);
    }
}
