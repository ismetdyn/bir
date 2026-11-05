package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.secrets.SecretService;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET} and {@code PUT /api/v1/secrets/<id>} (docs/SECRETS.md §3-4, ADR-0022 decision 5): the
 * id keeps its slashes, the value is the UTF-8 {@code text/plain} body. An unknown id and a value
 * this machine does not have answer the same empty 404. No token: OriginGuard keeps browsers out
 * (ADR-0020).
 */
@RestController
class SecretController {

    private static final String PATH = "/api/v1/secrets/{*id}";
    private static final MediaType TEXT_UTF8 =
            new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final SecretService secrets;

    SecretController(SecretService secrets) {
        this.secrets = secrets;
    }

    @GetMapping(PATH)
    ResponseEntity<byte[]> get(@PathVariable String id) {
        return secrets.value(id(id))
                .map(
                        value ->
                                ResponseEntity.ok()
                                        .contentType(TEXT_UTF8)
                                        .cacheControl(CacheControl.noStore())
                                        .body(value.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The body is read as bytes and decoded as UTF-8, whatever charset the request names. */
    @PutMapping(PATH)
    ResponseEntity<Void> put(
            @PathVariable String id, @RequestBody(required = false) byte @Nullable [] body) {
        String value = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        secrets.set(id(id), value);
        return ResponseEntity.noContent().build();
    }

    /** A capture-the-rest variable starts with the slash before it; it is empty without one. */
    private static String id(String captured) {
        return captured.startsWith("/") ? captured.substring(1) : captured;
    }
}
