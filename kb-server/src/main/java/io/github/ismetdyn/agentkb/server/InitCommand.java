package io.github.ismetdyn.agentkb.server;

import io.github.ismetdyn.agentkb.server.snapshot.RepoInit;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

/**
 * {@code java -jar agentkb-server.jar init} (docs/CLIENTS.md §2, ADR-0022 decision 3): creates the
 * data directory 0700 and the knowledge repository (RepoInit), without Spring and without Neo4j.
 * The paths are the server's defaults (AgentkbPaths, ADR-0027 decision 1), overridden by the same
 * AGENTKB_HOME, AGENTKB_DATA_DIR and AGENTKB_REPO the server reads. Idempotent: an existing data
 * directory is only made 0700 again and an existing git repository is left as it is (a second
 * machine clones its remote there before init). A repository path holding anything else is refused
 * untouched, before anything changes.
 */
final class InitCommand {

    static final String NAME = "init";
    private static final int OK = 0;
    private static final int REFUSED = 1;
    private static final int USAGE = 2;

    private final Path dataDir;
    private final Path repo;

    private InitCommand(Path dataDir, Path repo) {
        this.dataDir = dataDir;
        this.repo = repo;
    }

    /** The paths as the server resolves them by default. */
    static InitCommand fromEnvironment(AgentkbPaths paths) {
        return new InitCommand(paths.dataDir(), paths.repo());
    }

    /**
     * @param args what followed {@code init}; init takes none
     * @return the exit code: 0 done or already done, 1 refused or failed, 2 usage
     */
    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0) {
            err.println(
                    "init takes no arguments; set AGENTKB_REPO and AGENTKB_DATA_DIR to change"
                            + " the paths");
            return USAGE;
        }
        try {
            boolean isGitRepo = Files.exists(repo.resolve(".git"));
            if (!isGitRepo && !isAbsentOrEmpty(repo)) {
                err.println(
                        "init: "
                                + repo
                                + " exists and is not a git repository; nothing was changed."
                                + " Move it away, or clone the knowledge repository's remote"
                                + " there, then run init again.");
                return REFUSED;
            }
            ownerOnlyDataDir(out);
            if (isGitRepo) {
                out.println(repo + " is already a git repository; left as it is");
            } else {
                RepoInit.create(repo);
                out.println(
                        "created the knowledge repository " + repo + " (schema, empty snapshot)");
            }
            return OK;
        } catch (IOException e) {
            err.println("init failed: " + e.getMessage());
            return REFUSED;
        }
    }

    /** docs/SECRETS.md §5: the graph holds secret values, so its directory is the owner's only. */
    private void ownerOnlyDataDir(PrintStream out) throws IOException {
        boolean existed = Files.isDirectory(dataDir);
        Files.createDirectories(dataDir);
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
        out.println(
                (existed ? "data directory " : "created the data directory ")
                        + dataDir
                        + " (0700)");
    }

    private static boolean isAbsentOrEmpty(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return true;
        }
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
