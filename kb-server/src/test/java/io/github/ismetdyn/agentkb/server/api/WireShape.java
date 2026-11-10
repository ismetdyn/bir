package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire half of the kb-api contract for the H6 endpoints (ReadContractTest, WriteContractTest).
 */
final class WireShape {

    private static final JsonMapper JSON = JsonMapper.shared();

    private WireShape() {}

    /**
     * The answer read into {@code type} and written back is the answer itself: a field the record
     * lacks, or a record field the answer lacks, makes the two trees differ.
     */
    static void assertWireIs(HttpResponse<String> reply, Class<? extends Record> type, int status) {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(status);
        JsonNode wire = JSON.readTree(reply.body());

        JsonNode roundTrip = JSON.valueToTree(JSON.readValue(reply.body(), type));

        assertThat(roundTrip).isEqualTo(wire);
    }
}
