package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code kb import-memories} (docs/CLIENTS.md §2, docs/CURATOR.md §7): sends Claude Code auto
 * memory ({@code ~/.claude/projects/<project>/memory/*.md}) and Codex Memories (every {@code .md}
 * under {@code ~/.codex/memories/}) in one {@code POST /api/v1/signals/imported-memories}, each
 * with its path relative to the home directory. The server keys a signal by the content's hash, so
 * every run sends everything again and a second run opens nothing. Symbolic links are not followed:
 * a link in a memory folder may point anywhere. Exit codes as the maintenance commands: 0, 1
 * refused, 3 unreachable.
 */
@Command(
        name = "import-memories",
        description =
                "Import Claude Code auto memory and Codex Memories as imported-memory signals;"
                        + " run it before turning those memories off.")
final class ImportMemoriesCommand implements Callable<Integer> {

    private static final String PATH = "/api/v1/signals/imported-memories";
    private static final String SUFFIX = ".md";

    @Spec CommandSpec spec;

    private record Memory(String source, String path, String text) {}

    @Override
    public Integer call() throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        List<Memory> memories = new ArrayList<>();
        try (Stream<Path> projects = children(home.resolve(".claude/projects"))) {
            for (Path project :
                    projects.filter(dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                try (Stream<Path> files = children(project.resolve("memory"))) {
                    add(memories, "claude-code", home, files);
                }
            }
        }
        Path codex = home.resolve(".codex/memories");
        if (Files.isDirectory(codex, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.walk(codex)) {
                add(memories, "codex", home, files);
            }
        }
        PrintWriter out = spec.commandLine().getOut();
        if (memories.isEmpty()) {
            out.println(
                    "no memories found under ~/.claude/projects/*/memory/ or ~/.codex/memories/");
            return 0;
        }
        MemoryImportAnswer answer =
                Json.toRecord(
                        JsonApi.fromEnvironment().post(PATH, body(memories)),
                        MemoryImportAnswer.class);
        out.println(
                "sent "
                        + answer.received()
                        + " memories; "
                        + answer.opened()
                        + " opened a new imported-memory signal, the rest were imported before");
        return 0;
    }

    /** The entries of {@code dir}; none if it is missing or a link. */
    private static Stream<Path> children(Path dir) throws IOException {
        return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ? Files.list(dir) : Stream.empty();
    }

    /**
     * The regular {@code .md} files of {@code files}, in path order, so every run sends the same.
     */
    private static void add(List<Memory> memories, String source, Path home, Stream<Path> files) {
        files.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                .sorted()
                .forEach(
                        file -> memories.add(new Memory(source, relative(home, file), read(file))));
    }

    private static String relative(Path home, Path file) {
        StringJoiner path = new StringJoiner("/");
        home.relativize(file).forEach(part -> path.add(part.toString()));
        return path.toString();
    }

    /** As UTF-8; a byte that is no UTF-8 becomes U+FFFD instead of failing the import. */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KbException(
                    "cannot read " + file + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String body(List<Memory> memories) {
        StringJoiner list = new StringJoiner(", ", "{\"memories\": [", "]}");
        for (Memory memory : memories) {
            list.add(
                    "{\"source\": "
                            + JsonApi.quote(memory.source())
                            + ", \"path\": "
                            + JsonApi.quote(memory.path())
                            + ", \"text\": "
                            + JsonApi.quote(memory.text())
                            + "}");
        }
        return list.toString();
    }
}
