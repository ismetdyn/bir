package io.github.ismetdyn.agentkb.server.graph;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The schema of {@code <agentkb.repo>/schema} (SPEC §6.5). Read on every call: the files are small,
 * and an accepted schema change then applies without a restart. The server starts without a repo
 * (SnapshotImporter), so a missing schema fails the call that needs it, not the start.
 */
@Component
public class RepoSchema {

    private static final String SCHEMA_DIRECTORY = "schema";

    private final String repo;

    public RepoSchema(@Value("${agentkb.repo:}") String repo) {
        this.repo = repo;
    }

    /**
     * @throws IllegalStateException if no repo is set or a schema file is missing or broken
     */
    public Schema load() {
        return Schema.load(directory());
    }

    /**
     * The schema directory, where an accepted schema proposal is written (ADR-0025 decision 12).
     *
     * @throws IllegalStateException if no repo is set
     */
    public Path directory() {
        if (repo.isBlank()) {
            throw new IllegalStateException("agentkb.repo is not set; there is no schema");
        }
        return Path.of(repo).resolve(SCHEMA_DIRECTORY);
    }
}
