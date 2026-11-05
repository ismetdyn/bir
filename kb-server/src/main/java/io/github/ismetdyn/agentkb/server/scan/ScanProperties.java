package io.github.ismetdyn.agentkb.server.scan;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code agentkb.scan} (ARCHITECTURE §14). The scanner reads only under {@code allowedRoots}
 * (invariant 9); a host in one of {@code internalDomains} gets its first DNS label as entity id
 * (fixtures/scanner/expected.yaml).
 */
@ConfigurationProperties("agentkb.scan")
public record ScanProperties(
        @Nullable List<Path> allowedRoots, @Nullable List<String> internalDomains) {

    public ScanProperties {
        allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
        internalDomains = internalDomains == null ? List.of() : List.copyOf(internalDomains);
    }
}
