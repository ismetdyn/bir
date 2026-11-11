package io.github.ismetdyn.agentkb.server.docs;

import io.github.ismetdyn.agentkb.server.graph.OwnerOnly;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code kb doctor}, {@code GET /api/v1/doctor} (docs/CLIENTS.md §2): git, git-lfs, the knowledge
 * repo, the git-lfs filter set with {@code --local} in that repo when its .gitattributes uses it
 * (ADR-0025 decision 5), and the owner-only data directory (kb init makes it 0700, or owner-only by
 * ACL on Windows). Nothing is changed. A hint names the setting, never a path: the answer goes to
 * any local client.
 */
@Component
public class Doctor {

    public record Report(List<Check> checks) {

        public Report {
            checks = List.copyOf(checks);
        }

        public boolean ok() {
            return checks.stream().allMatch(Check::ok);
        }
    }

    /** {@code hint} is set only when the check fails. */
    public record Check(String name, boolean ok, @Nullable String hint) {}

    private static final String LFS_ATTRIBUTE = "filter=lfs";

    private final String repo;
    private final Path dataDir;

    public Doctor(
            @Value("${agentkb.repo:}") String repo, @Value("${agentkb.data-dir}") Path dataDir) {
        this.repo = repo;
        this.dataDir = dataDir;
    }

    public Report check() throws IOException {
        boolean repoOk = !repo.isBlank() && Files.isDirectory(Path.of(repo).resolve(".git"));
        return new Report(
                List.of(
                        check(
                                "git",
                                GitLfs.gitPresent(),
                                "Install git; kb sync and kb check need it."),
                        check(
                                "git-lfs",
                                GitLfs.present(),
                                "Install git-lfs; documents over the LFS threshold need it."),
                        check(
                                "repo",
                                repoOk,
                                "Run kb init, or start the server with --agentkb.repo set to the"
                                        + " knowledge repository."),
                        check(
                                "lfs-filter",
                                !repoOk || !usesLfs() || GitLfs.localFilterSet(Path.of(repo)),
                                "Run git lfs install --local in the knowledge repository; its"
                                        + " .gitattributes stores documents with git-lfs."),
                        check(
                                "data-dir",
                                ownerOnly(),
                                "Make the data directory (agentkb.data-dir) owner-only: chmod"
                                        + " 700.")));
    }

    private boolean usesLfs() throws IOException {
        Path attributes = Path.of(repo).resolve(".gitattributes");
        if (!Files.isRegularFile(attributes, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        // Line by line, not whole (CP4 S3): the file came with a sync and may be large. Every
        // byte maps in ISO-8859-1, so no encoding error stops the read; the attribute is ASCII.
        try (BufferedReader lines =
                Files.newBufferedReader(attributes, StandardCharsets.ISO_8859_1)) {
            return lines.lines().anyMatch(line -> line.contains(LFS_ATTRIBUTE));
        }
    }

    private boolean ownerOnly() throws IOException {
        return Files.isDirectory(dataDir, LinkOption.NOFOLLOW_LINKS) && OwnerOnly.holds(dataDir);
    }

    private static Check check(String name, boolean ok, String hint) {
        return new Check(name, ok, ok ? null : hint);
    }
}
