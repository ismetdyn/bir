package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** KbApi takes only this machine (review 5), as AGENTKB_URL does, whatever the caller passes. */
class KbApiTest {

    @Test
    void a_server_on_another_machine_is_refused() {
        assertThatThrownBy(() -> new KbApi("http://192.0.2.10:7411", KbApi.INTERACTIVE))
                .isInstanceOf(KbException.class)
                .hasMessageContaining("must name this machine");
        assertThatThrownBy(() -> new KbApi("http://example.org", KbApi.INTERACTIVE))
                .isInstanceOf(KbException.class);
    }

    @Test
    void a_stub_on_loopback_is_taken() throws IOException {
        try (ApiStub stub = new ApiStub().answer("GET", "/api/v1/schema", 200, "{}")) {
            KbApi api = new KbApi(stub.url() + "/", KbApi.INTERACTIVE);

            assertThatThrownBy(api::schema).isInstanceOf(KbException.class);
            assertThat(stub.requests())
                    .extracting(ApiStub.Request::rawPath)
                    .containsExactly("/api/v1/schema");
        }
    }
}
