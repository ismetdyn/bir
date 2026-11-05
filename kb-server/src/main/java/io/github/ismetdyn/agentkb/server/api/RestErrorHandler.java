package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one top-level REST error handler (ADR-0022 decision 19). The dispatcherServlet logger is
 * masked, so this logs an unexpected failure itself: its class and a {@code trace_id}, never its
 * message, which may carry a request value (invariants 2 and 10). The reply carries the trace id
 * and no internal detail. Limited to the REST and hook controllers, so the MCP transports keep
 * their own handling; kb-hook.sh turns any non-2xx hook answer into no output (fail-open).
 */
@RestControllerAdvice(
        basePackages = {
            "io.github.ismetdyn.agentkb.server.api",
            "io.github.ismetdyn.agentkb.server.hooks"
        })
class RestErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RestErrorHandler.class);

    /** A refused write: what is wrong and how to go on, never a value (WriteRejectedException). */
    @ExceptionHandler(WriteRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(WriteRejectedException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "hint", e.hint()));
    }

    /**
     * Spring's own request errors (unreadable body, wrong method) keep their 4xx status; a
     * malformed request is not a server failure. Everything else is a 500.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        if (e instanceof ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).build();
        }
        String traceId = UUID.randomUUID().toString();
        LOG.error("event=rest_failed error={} trace_id={}", e.getClass().getName(), traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal error", "trace_id", traceId));
    }
}
