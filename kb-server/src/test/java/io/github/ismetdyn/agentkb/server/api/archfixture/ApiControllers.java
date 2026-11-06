package io.github.ismetdyn.agentkb.server.api.archfixture;

import io.github.ismetdyn.agentkb.api.archfixture.ApiRecord;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Handler methods under the api package for ArchitectureRulesTest. Not annotated as controllers, so
 * Spring never maps them; only their class files are read.
 */
public final class ApiControllers {

    private ApiControllers() {}

    /** Permitted: server.api maps to kb-api records. */
    public static final class UsesApiRecord {
        @GetMapping("/archfixture/api-record")
        ApiRecord get() {
            return new ApiRecord("id", null, List.of(), 0);
        }

        @PostMapping("/archfixture/response-entity")
        ResponseEntity<Void> post() {
            return ResponseEntity.noContent().build();
        }
    }

    /** Violation: a domain record put on the wire as it is. */
    public static final class ReturnsDomainRecord {
        @PostMapping("/archfixture/domain-record")
        RevertResult revert() {
            return new RevertResult("batch", 0, 0, 0);
        }
    }
}
