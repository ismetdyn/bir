package io.github.ismetdyn.agentkb.server.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The document store in the git of {@code kb sync} (ADR-0025 decision 5): {@code blobs/} and {@code
 * .gitattributes} travel with the snapshot. A {@code .tmp} under blobs/ is a copy still being
 * written and stays out.
 */
final class DocumentStoreGit {

    static final String NOT_TMP = ":(exclude)*.tmp";
    private static final List<String> PATHS = List.of("blobs", ".gitattributes");
    private static final String UNION_ATTRIBUTES = "/.gitattributes merge=union";
    private static final List<String> LINE_ENDING_ATTRIBUTES =
            List.of("snapshot/*.jsonl text eol=lf", "blobs/** -text");
    private final Path repo;

    DocumentStoreGit(Path repo) {
        this.repo = repo;
    }

    void stage() throws IOException {
        // git refuses a path that matches nothing, so only those that exist.
        List<String> present =
                PATHS.stream().filter(path -> Files.exists(repo.resolve(path))).toList();
        if (!present.isEmpty()) {
            List<String> add = new ArrayList<>(List.of("add", "--all", "--"));
            add.addAll(present);
            add.add(NOT_TMP);
            SyncGit.require(
                    git(add.toArray(String[]::new)), "git add of the document store failed");
        }
    }

    /** Puts the store paths in the index back to HEAD; the working tree keeps the files. */
    void unstage() throws IOException {
        List<String> reset = new ArrayList<>(List.of("reset", "--quiet", "--"));
        reset.addAll(PATHS);
        git(reset.toArray(String[]::new));
    }

    /** The document store paths whose staged state differs from HEAD. */
    List<String> staged() throws IOException {
        // A commit of paths refuses one git does not know, so only the store paths with changes.
        List<String> store = new ArrayList<>();
        for (String path : PATHS) {
            if (!git("diff", "--cached", "--quiet", "--", path).ok()) {
                store.add(path);
            }
        }
        return store;
    }

    /** Commits local changes of the document store alone; other staged files stay staged. */
    void commitAlone() throws IOException {
        stage();
        List<String> store = staged();
        if (store.isEmpty()) {
            return;
        }
        List<String> commit =
                new ArrayList<>(List.of("commit", "--quiet", "-m", "kb sync: documents", "--"));
        commit.addAll(store);
        commit.add(NOT_TMP);
        SyncGit.require(
                git(SystemGit.asServer(commit.toArray(String[]::new))),
                "git commit of the document store failed");
    }

    /**
     * {@code .gitattributes} merges by union, {@code -X ours} or not: two machines that each track
     * a blob with git-lfs append a line each, and ours alone would drop the other's, so its blob
     * would be committed raw. Set in {@code $GIT_DIR/info/attributes}, so it holds for whatever
     * wrote {@code .gitattributes} and changes no tracked file.
     */
    void mergeByUnion() throws IOException {
        // SystemGit pins GIT_DIR to <repo>/.git.
        appendMissing(
                repo.resolve(".git").resolve("info").resolve("attributes"),
                ".git/info/attributes",
                List.of(UNION_ATTRIBUTES));
    }

    /**
     * ADR-0027 decision 8: the tracked {@code .gitattributes} keeps the snapshot LF and the blobs
     * byte for byte, so {@code core.autocrlf} on a Windows clone changes neither the snapshot's
     * bytes nor a blob's hash. Tracked, unlike the union line, as it must reach every clone; kb
     * init writes it and a sync adds it to an older repository.
     */
    void keepLineEndings() throws IOException {
        appendMissing(
                repo.resolve(".gitattributes"),
                ".gitattributes in the knowledge repository",
                LINE_ENDING_ATTRIBUTES);
    }

    /**
     * Appends the lines of {@code wanted} that {@code file} lacks, each once. The file may hold the
     * user's own lines in any encoding: it is read as ISO-8859-1, which maps every byte, and lines
     * are only appended, so a crash cannot lose what was there. A link there is refused, never
     * written through (CWE-59).
     */
    private static void appendMissing(Path file, String name, List<String> wanted)
            throws IOException {
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " is a link or no plain file");
        }
        List<String> missing = new ArrayList<>(wanted);
        // Line by line, not whole (CP4 S3); only the last byte decides the separator.
        if (exists) {
            try (Stream<String> lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
                lines.forEach(missing::remove);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Files.createDirectories(file.getParent());
        String separator = exists && !endsWithLineBreak(file) ? "\n" : "";
        Files.writeString(
                file,
                separator + String.join("\n", missing) + "\n",
                StandardCharsets.ISO_8859_1,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** An empty file counts as ending with a line break: nothing goes before the new line. */
    private static boolean endsWithLineBreak(Path file) throws IOException {
        try (SeekableByteChannel channel =
                Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size == 0) {
                return true;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1).read(last);
            return last.get(0) == '\n';
        }
    }

    private SystemGit.Result git(String... args) throws IOException {
        return SystemGit.run(repo, false, args);
    }
}
