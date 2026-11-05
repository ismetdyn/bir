package io.github.ismetdyn.agentkb.server.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
        return new Snapshot(
                readRows(dir.resolve(NODES_FILE), Node.class),
                readRows(dir.resolve(EDGES_FILE), Edge.class));
    }

    public static void write(Path dir, Snapshot snapshot) throws IOException {
        Files.createDirectories(dir);
        List<Map<String, ?>> nodes = new ArrayList<>();
        for (Node node : snapshot.nodes()) {
            nodes.add(Map.of("id", node.id(), "labels", node.labels(), "props", node.props()));
        }
        List<Map<String, ?>> edges = new ArrayList<>();
        for (Edge edge : snapshot.edges()) {
            edges.add(
                    Map.of(
                            "id", edge.id(),
                            "type", edge.type(),
                            "from", edge.from(),
                            "to", edge.to(),
                            "props", edge.props()));
        }
        writeRows(dir.resolve(NODES_FILE), nodes);
        writeRows(dir.resolve(EDGES_FILE), edges);
    }

    private static <T> List<T> readRows(Path file, Class<T> type) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<T> rows = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            try {
                rows.add(JSON.readValue(lines.get(i), type));
            } catch (JacksonException e) {
                throw new IOException(file.getFileName() + " line " + (i + 1) + ": not a row", e);
            }
        }
        return rows;
    }

    private static void writeRows(Path file, List<Map<String, ?>> rows) throws IOException {
        List<Map<String, ?>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(row -> (String) row.get("id")));
        StringBuilder out = new StringBuilder();
        for (Map<String, ?> row : sorted) {
            appendValue(out, row);
            out.append('\n');
        }
        Files.writeString(file, out, StandardCharsets.UTF_8);
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
