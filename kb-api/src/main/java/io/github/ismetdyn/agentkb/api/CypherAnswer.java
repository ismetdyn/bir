package io.github.ismetdyn.agentkb.api;

import java.util.List;

/**
 * {@code POST /api/v1/cypher}: the columns of a read-only query and its rows as text, at most the
 * rows and characters kb_cypher shows; {@code truncated} if more were cut. A null cell is empty
 * text. No cell ever holds a secret value.
 */
public record CypherAnswer(List<String> columns, List<Row> rows, boolean truncated) {

    public CypherAnswer {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    /** The cells of one row, in the order of {@code columns}. */
    public record Row(List<String> cells) {

        public Row {
            cells = List.copyOf(cells);
        }
    }
}
