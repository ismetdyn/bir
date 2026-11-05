package io.github.ismetdyn.agentkb.server.mcp.worker;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the Markdown table kb_cypher answers with. */
final class MarkdownTable {

    private static final Pattern RULE = Pattern.compile("^\\|[\\s:|-]+\\|$");
    private static final Pattern OUTER_PIPES = Pattern.compile("^\\||\\|$");

    private MarkdownTable() {}

    /**
     * The cells of each table line, header first, the {@code |---|} line dropped. Only lines that
     * start with {@code |} belong to the table.
     */
    static List<List<String>> cells(String text) {
        return text.lines()
                .filter(line -> line.startsWith("|"))
                .filter(line -> !RULE.matcher(line).matches())
                .map(
                        line ->
                                Stream.of(
                                                OUTER_PIPES
                                                        .matcher(line.strip())
                                                        .replaceAll("")
                                                        .split("\\|", -1))
                                        .map(String::strip)
                                        .toList())
                .toList();
    }
}
