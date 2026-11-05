package io.github.ismetdyn.agentkb.server.write;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One scan of a working copy as the scanner hands it to {@link WriteService#scan} (ARCHITECTURE
 * §6): the project, its Repo and what each file held. Envs are raw names (profile, file suffix);
 * the write maps them through facts.yaml#environments. Nothing here is trusted: every fact and
 * relation is checked like a new write (ADR-0022 decision 31).
 *
 * @param repo the Repo id ({@code <group>/<name>})
 * @param remote the normalized remote, null without one
 * @param commit HEAD of the working copy, null before the first commit
 * @param skipped files the scanner could not read, as {@code <path>: <reason>}
 */
public record ScanWrite(
        String project,
        String repo,
        @Nullable String remote,
        @Nullable String commit,
        List<ScannedFile> files,
        List<String> skipped,
        Instant at) {

    public ScanWrite {
        files = List.copyOf(files);
        skipped = List.copyOf(skipped);
    }

    /** What one file held; {@code path} is relative to the repository root. */
    public record ScannedFile(
            String path, List<Found> facts, List<Link> links, List<FoundSecret> secrets) {

        public ScannedFile {
            facts = List.copyOf(facts);
            links = List.copyOf(links);
            secrets = List.copyOf(secrets);
        }
    }

    /**
     * A fact on {@code entity} (an id; null is the project), created with {@code type} if new, from
     * {@code line} of its file; {@code target} is the entity a port fact points to (TARGET), or
     * null.
     */
    public record Found(
            @Nullable String entity,
            @Nullable String type,
            String key,
            String value,
            String env,
            int line,
            @Nullable String target) {

        public Found(
                @Nullable String entity,
                @Nullable String type,
                String key,
                String value,
                String env,
                int line) {
            this(entity, type, key, value, env, line, null);
        }
    }

    /** A relation from the project to the entity {@code target} of a fact of the same file. */
    public record Link(String type, String target, String env) {}

    /** A secret of the project; the value goes to secret_value only (invariant 2). */
    public record FoundSecret(String name, String env, String value, int line) {

        @Override
        public String toString() {
            return "FoundSecret[name=" + name + ", env=" + env + ", line=" + line + "]";
        }
    }
}
