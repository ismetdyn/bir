package io.github.ismetdyn.agentkb.server.security;

import static io.github.ismetdyn.agentkb.server.security.RawHttp.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.security.RawHttp.Reply;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0020: the H7 read endpoints sit behind OriginGuard like every path. A web page (any Origin,
 * or a foreign Host after DNS rebinding) reads neither the document list, a document's path nor the
 * doctor report.
 */
class DocumentEndpointGuardTest extends SecurityTestBase {

    private static final String ORIGIN = "Origin: http://attacker.example";

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/documents", "/api/v1/documents/doc:ded48257b6f3", "/api/v1/doctor"})
    void a_browser_request_reads_nothing(String path) throws Exception {
        Reply withOrigin = RawHttp.send(port, HTTP_1_1, "GET", path, "", host(), ORIGIN);
        Reply forgedHost =
                RawHttp.send(port, HTTP_1_1, "GET", path, "", "Host: attacker.example:" + port);

        assertThat(withOrigin.status()).isEqualTo(403);
        assertThat(forgedHost.status()).isEqualTo(403);
        assertThat(withOrigin.body() + forgedHost.body())
                .doesNotContain("ded48257b6f3", "blobs/", "checks");
    }
}
