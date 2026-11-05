package io.github.ismetdyn.agentkb.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The example Cypher queries of docs/MCP-TOOLS.md "Örnek sorgular", the one place they are written.
 * kb_schema must show them and CypherGuard must let them through; reading them from the document
 * keeps the tests from drifting away from it.
 */
public final class ExampleQueries {

    private static final Path DOC = Path.of("..", "docs", "MCP-TOOLS.md");
    // The first fenced block after the section heading; the heading may carry a note after it.
    private static final Pattern BLOCK =
            Pattern.compile(
                    "## Örnek sorgular(?: [^\\r\\n]*)?\\R.*?```cypher\\R(.*?)```", Pattern.DOTALL);
    private static final Pattern COMMENT_LINE = Pattern.compile("(?m)^\\s*//.*$");
    private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
    // The document numbers 13 queries; a parse that finds another count has silently broken.
    public static final int COUNT = 13;

    private ExampleQueries() {}

    /** Each query on one line, its lines joined by a space; the numbered comments split them. */
    public static List<String> fromDocs() {
        Matcher block = BLOCK.matcher(read());
        if (!block.find()) {
            throw new IllegalStateException(DOC + " has no example query block");
        }
        List<String> queries =
                Stream.of(COMMENT_LINE.split(block.group(1)))
                        .map(ExampleQueries::squeeze)
                        .filter(query -> !query.isEmpty())
                        .toList();
        if (queries.size() != COUNT) {
            throw new IllegalStateException(
                    "expected " + COUNT + " example queries in " + DOC + ", found " + queries);
        }
        return queries;
    }

    /** Runs of white space as one space, so a query broken over lines still matches. */
    public static String squeeze(String text) {
        return WHITE_SPACE.matcher(text).replaceAll(" ").strip();
    }

    private static String read() {
        try {
            return Files.readString(DOC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
