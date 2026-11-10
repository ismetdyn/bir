package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code POST /api/v1/scan}: what one scan wrote as counts and ids. {@code secrets} are
 * placeholders; the values stay on this machine (invariant 2). {@code skipped} is {@code
 * <path>[:<line>]: <reason>}.
 */
public record ScanAnswer(
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

    public ScanAnswer {
        newEntities = List.copyOf(newEntities);
        secrets = List.copyOf(secrets);
        findings = List.copyOf(findings);
        skipped = List.copyOf(skipped);
    }
}
