package io.github.ismetdyn.agentkb.server.mcp.worker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * kb_cypher rows as a Markdown table: a header of the columns, one line per row. Rows past {@code
 * maxRows} or the character budget are cut, and the last line says so (ADR-0017 decision 4).
 */
final class CypherTable {

    private static final String NONE = "Sonuç yok.\n";
    private static final String CUT =
            "(kısaltıldı: ilk %d satır gösterildi; sorguyu LIMIT veya WHERE ile daralt)\n";
    // Room for the cut line, whatever the row count.
    private static final int CUT_RESERVE = CUT.length() + 10;

    private CypherTable() {}

    static String render(List<Map<String, Object>> rows, int maxRows, int maxChars) {
        if (rows.isEmpty()) {
            return NONE;
        }
        List<String> columns = List.copyOf(rows.getFirst().keySet());
        StringBuilder out = new StringBuilder(line(columns));
        out.append(columns.stream().map(column -> "---").collect(joined()));
        int shown = 0;
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), maxRows))) {
            String line = line(columns.stream().map(row::get).toList());
            if (out.length() + line.length() + CUT_RESERVE > maxChars) {
                break;
            }
            out.append(line);
            shown++;
        }
        if (shown < rows.size()) {
            out.append(CUT.formatted(shown));
        }
        return out.toString();
    }

    private static String line(List<?> cells) {
        return cells.stream().map(CypherTable::cell).collect(joined());
    }

    private static Collector<CharSequence, ?, String> joined() {
        return Collectors.joining(" | ", "| ", " |\n");
    }

    // A pipe or a line break would end the cell or the row.
    private static String cell(Object value) {
        return text(value).replace("|", "\\|").replaceAll("\\R", " ");
    }

    private static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Map<?, ?> map ->
                    map.entrySet().stream()
                            .map(entry -> entry.getKey() + ": " + text(entry.getValue()))
                            .collect(Collectors.joining(", ", "{", "}"));
            case List<?> list ->
                    list.stream()
                            .map(CypherTable::text)
                            .collect(Collectors.joining(", ", "[", "]"));
            default -> String.valueOf(value);
        };
    }
}
