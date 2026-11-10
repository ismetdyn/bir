package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.api.DoctorAnswer;
import io.github.ismetdyn.agentkb.api.DocumentAnswer;
import io.github.ismetdyn.agentkb.api.DocumentListAnswer;
import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server half of the kb-api contract for the H7 endpoints (ApiContractTest explains the
 * method): {@code GET /api/v1/documents}, {@code GET /api/v1/documents/{id}} and {@code GET
 * /api/v1/doctor}. Each record is written as exactly its file under fixtures/api/, and each
 * endpoint answers with the fields of its record, no more and no fewer.
 */
class DocumentApiContractTest extends RunningServerTestBase {

    private static final String SEED_SHA =
            "ded48257b6f34156d85bfd03c6aa6c4d3724d713d417771e1ccf7c68d398dbf4";
    private static final String OTHER_SHA =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9";

    @Autowired private JsonMapper json;

    static Stream<Arguments> samples() {
        return Stream.of(
                arguments(
                        "document-answer.json",
                        new DocumentAnswer(
                                "doc:ded48257b6f3",
                                "ornek-proje-çalıştırma İ.md",
                                SEED_SHA,
                                "text/markdown",
                                263L,
                                "wiki.ornek.example/ornek-proje/calistirma",
                                "2026-10-30T14:00:00Z",
                                "/home/kbtest/kb/blobs/de/" + SEED_SHA,
                                List.of("ornek-proje", "ornek-app-01"))),
                arguments(
                        "document-list-answer.json",
                        new DocumentListAnswer(
                                List.of(
                                        new DocumentAnswer(
                                                "doc:0a1b2c3d4e5f",
                                                "zz-big.bin",
                                                OTHER_SHA,
                                                "application/octet-stream",
                                                26_214_400L,
                                                null,
                                                "2026-11-10T09:30:00Z",
                                                "/home/kbtest/kb/blobs/0a/" + OTHER_SHA,
                                                List.of())),
                                true)),
                arguments(
                        "doctor-answer.json",
                        new DoctorAnswer(
                                false,
                                List.of(
                                        new DoctorAnswer.Check("git", true, null),
                                        new DoctorAnswer.Check(
                                                "git-lfs",
                                                false,
                                                "Install git-lfs; documents over the LFS"
                                                        + " threshold need it."),
                                        new DoctorAnswer.Check("repo", true, null),
                                        new DoctorAnswer.Check("lfs-filter", true, null),
                                        new DoctorAnswer.Check(
                                                "data-dir",
                                                false,
                                                "Make the data directory (agentkb.data-dir)"
                                                        + " owner-only: chmod 700.")))));
    }

    @ParameterizedTest
    @MethodSource("samples")
    void the_server_writes_each_record_as_its_contract_file(String file, Record sample)
            throws IOException {
        // Through text, as the client reads it: valueToTree keeps a Long a LongNode, readTree makes
        // a small number an IntNode, and the two would differ on the type alone.
        JsonNode written = json.readTree(json.writeValueAsString(sample));

        assertThat(written)
                .isEqualTo(
                        json.readTree(
                                Files.readString(
                                        ApiContractTest.CONTRACT.resolve(file),
                                        StandardCharsets.UTF_8)));
    }

    @Test
    void the_document_list_answers_with_the_fields_of_document_list_answer() throws Exception {
        assertWireIs(get(DocumentHttp.DOCUMENTS), DocumentListAnswer.class);
    }

    @Test
    void one_document_answers_with_the_fields_of_document_answer() throws Exception {
        assertWireIs(get(DocumentHttp.DOCUMENTS + "/doc:ded48257b6f3"), DocumentAnswer.class);
    }

    @Test
    void doctor_answers_with_the_fields_of_doctor_answer() throws Exception {
        assertWireIs(get("/api/v1/doctor"), DoctorAnswer.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return new MaintenanceHttp(port).get(path);
    }

    /** Read into {@code type} and written back, the answer is unchanged (ApiContractTest). */
    private void assertWireIs(HttpResponse<String> reply, Class<? extends Record> type) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        JsonNode wire = json.readTree(reply.body());

        JsonNode roundTrip =
                json.readTree(json.writeValueAsString(json.readValue(reply.body(), type)));

        assertThat(roundTrip).isEqualTo(wire);
    }
}
