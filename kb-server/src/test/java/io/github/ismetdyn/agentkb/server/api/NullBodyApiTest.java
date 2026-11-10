package io.github.ismetdyn.agentkb.server.api;

import static io.github.ismetdyn.agentkb.server.api.ApiHttp.assertRefused;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lead H6 review 7: a JSON body that is the literal {@code null} is the client's error: 400 {@code
 * {error, hint}}, never a 500.
 */
class NullBodyApiTest extends RunningServerTestBase {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "POST, /api/v1/entities",
        "POST, /api/v1/entities/ornek-app-01/facts",
        "POST, /api/v1/entities/ornek-app-01/relations",
        "PATCH, /api/v1/entities/ornek-app-01",
        "POST, /api/v1/cypher",
        "POST, /api/v1/scan",
        "POST, /api/v1/conflicts/zz-h6-none/resolve",
        "POST, /api/v1/proposals/zz-h6-none/reject"
    })
    void a_null_body_is_refused(String method, String path) throws Exception {
        ApiHttp api = new ApiHttp(port);
        HttpResponse<String> reply =
                method.equals("PATCH") ? api.patch(path, "null") : api.post(path, "null");

        assertRefused(reply, 400);
    }
}
