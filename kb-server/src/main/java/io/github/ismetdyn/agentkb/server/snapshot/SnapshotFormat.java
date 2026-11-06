package io.github.ismetdyn.agentkb.server.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The snapshot files {@code nodes.jsonl} and {@code edges.jsonl} (ADR-0022 decision 9). One JSON
 * object per line, rows sorted by id, keys recursively alphabetical, raw UTF-8, "\n" line ends. The
 * bytes match Python's {@code json.dumps(ensure_ascii=False, sort_keys=True)}, which writes the
 * golden seed files, so the writer is hand-rolled: Jackson's spacing and escapes differ.
 */
public final class SnapshotFormat {

    public static final String NODES_FILE = "nodes.jsonl";
    public static final String EDGES_FILE = "edges.jsonl";

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int FIRST_PRINTABLE = 0x20;
    // Python's escape table for ensure_ascii=False: quote, backslash and control characters only.
    private static final Map<Character, String> SHORT_ESCAPES =
            Map.of(
                    '"', "\\\"",
                    '\\', "\\\\",
                    '\n', "\\n",
                    '\r', "\\r",
                    '\t', "\\t",
                    '\b', "\\b",
                    '\f', "\\f");

    private SnapshotFormat() {}

    /** A node row. Labels keep their order; Neo4j does not, so the exporter fixes one. */
    public record Node(String id, List<String> labels, Map<String, Object> props) {}

    /** An edge row; {@code from} and {@code to} are node ids. */
    public record Edge(String id, String type, String from, String to, Map<String, Object> props) {}

    public record Snapshot(List<Node> nodes, List<Edge> edges) {}

    public static Snapshot read(Path dir) throws IOException {
        return parse(
                Files.readString(dir.resolve(NODES_FILE), StandardCharsets.UTF_8),
                Files.readString(dir.resolve(EDGES_FILE), StandardCharsets.UTF_8));
    }

    /** The two files' text, as git gives a committed version of them (SnapshotHistory). */
    static Snapshot parse(String nodes, String edges) throws IOException {
        return new Snapshot(
                rows(NODES_FILE, nodes, Node.class), rows(EDGES_FILE, edges, Edge.class));
    }

    /**
     * ADR-0022 decision 43: both texts are built first, so a row with no JSON form fails before
     * either file changes; each file is then written to a temporary file and renamed over the old
     * one. A crash between the two renames can still leave a new nodes.jsonl next to an old
     * edges.jsonl.
     */
    public static void write(Path dir, Snapshot snapshot) throws IOException {
        Files.createDirectories(dir);
        String nodes =
                snapshot.nodes().stream()
                        .sorted(Comparator.comparing(Node::id))
                        .map(node -> row(node) + "\n")
                        .collect(Collectors.joining());
        String edges =
                snapshot.edges().stream()
                        .sorted(Comparator.comparing(Edge::id))
                        .map(edge -> row(edge) + "\n")
                        .collect(Collectors.joining());
        replace(dir.resolve(NODES_FILE), nodes);
        replace(dir.resolve(EDGES_FILE), edges);
    }

    /** The line of a {@link Node} or {@link Edge} in its file, without the line end. */
    static String row(Record record) {
        Map<String, ?> fields =
                switch (record) {
                    case Node node ->
                            Map.of("id", node.id(), "labels", node.labels(), "props", node.props());
                    case Edge edge ->
                            Map.of(
                                    "id", edge.id(),
                                    "type", edge.type(),
                                    "from", edge.from(),
                                    "to", edge.to(),
                                    "props", edge.props());
                    default ->
                            throw new IllegalArgumentException(
                                    "not a snapshot row: " + record.getClass().getName());
                };
        StringBuilder out = new StringBuilder();
        appendValue(out, fields);
        return out.toString();
    }

    private static <T> List<T> rows(String file, String text, Class<T> type) throws IOException {
        List<String> lines = text.lines().toList();
        List<T> rows = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            try {
                rows.add(JSON.readValue(lines.get(i), type));
            } catch (JacksonException e) {
                throw new IOException(file + " line " + (i + 1) + ": not a row", e);
            }
        }
        return rows;
    }

    private static void replace(Path file, String text) throws IOException {
        Path temporary =
                Files.createTempFile(file.getParent(), "." + file.getFileName() + "-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void appendValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> appendString(out, text);
            case Boolean bool -> out.append(bool);
            // ponytail: Double.toString differs from Python's repr for exponents (1.0E-5 vs
            // 1e-05); the seed has no floats. Match repr if a float property ever appears.
            case Number number -> out.append(number);
            case Map<?, ?> map -> appendObject(out, map);
            case List<?> list -> appendArray(out, list);
            default ->
                    throw new IllegalArgumentException(
                            "no JSON form for " + value.getClass().getName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        String separator = "";
        for (Map.Entry<?, ?> entry : new TreeMap<>(map).entrySet()) {
            out.append(separator);
            appendString(out, (String) entry.getKey());
            out.append(": ");
            appendValue(out, entry.getValue());
            separator = ", ";
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> list) {
        out.append('[');
        String separator = "";
        for (Object item : list) {
            out.append(separator);
            appendValue(out, item);
            separator = ", ";
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String escape = SHORT_ESCAPES.get(c);
            if (escape != null) {
                out.append(escape);
            } else if (c < FIRST_PRINTABLE) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}
