package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.SecretMask;
import java.util.List;

/**
 * The ScanReport of ARCHITECTURE §6 as counts and ids; it holds no secret value. {@code secrets}
 * are secret ids, {@code findings} the values of the finding facts written, {@code skipped} what
 * was not written, as {@code <path>[:<line>]: <reason>}.
 */
public record ScanResult(
        String project,
        String repo,
        String batch,
        int files,
        List<String> newEntities,
        int added,
        int refreshed,
        int closed,
        int conflicts,
        List<String> secrets,
        List<String> findings,
        List<String> skipped) {

    public ScanResult {
        newEntities = List.copyOf(newEntities);
        secrets = List.copyOf(secrets);
        findings = List.copyOf(findings);
        skipped = List.copyOf(skipped);
    }

    /** ADR-0026: the ids and texts as an answer shows them; the batch is a ULID. */
    ScanResult masked(SecretMask mask) {
        return new ScanResult(
                mask.text(project),
                mask.text(repo),
                batch,
                files,
                newEntities.stream().map(mask::text).toList(),
                added,
                refreshed,
                closed,
                conflicts,
                secrets.stream().map(mask::text).toList(),
                findings.stream().map(mask::text).toList(),
                skipped.stream().map(mask::text).toList());
    }
}
