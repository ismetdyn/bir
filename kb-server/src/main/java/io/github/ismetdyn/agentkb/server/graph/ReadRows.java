package io.github.ismetdyn.agentkb.server.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * The output boundary of the reads (invariant 2): every row a read answer is made of comes through
 * here with the values stored on Secrets masked (SecretMask), whether the answer is REST JSON, MCP
 * or hook Markdown, or a kb_cypher table. The masking is on the rows, before a snippet or a budget
 * cuts a text, so no part of a value is left at a cut. One per read transaction, which reads the
 * stored values once.
 */
public final class ReadRows {

    private final Transaction tx;
    private final SecretMask mask;

    private ReadRows(Transaction tx, SecretMask mask) {
        this.tx = tx;
        this.mask = mask;
    }

    /** The rows of the read transaction {@code tx}, masked with the values stored now. */
    public static ReadRows in(Transaction tx) {
        return new ReadRows(tx, SecretMask.in(tx));
    }

    /** Each row of {@code query} with every text masked, then mapped. */
    public <T> List<T> rows(
            String query, Map<String, Object> params, Function<Map<String, Object>, T> mapper) {
        List<T> mapped = new ArrayList<>();
        try (Result result = tx.execute(query, params)) {
            result.forEachRemaining(row -> mapped.add(mapper.apply(mask.row(row))));
        }
        return mapped;
    }

    /** {@code text} as an answer shows it, e.g. the id a read was asked for. */
    public String shown(String text) {
        return mask.text(text);
    }

    /** At most {@code limit} rows of the agent's own query (CypherRows). */
    List<Map<String, Object>> cypher(String query, int limit) {
        try (Result result = tx.execute(query)) {
            return new CypherRows(mask).rows(result, limit);
        }
    }

    /**
     * The {@code id} column of each row, unmasked: ids that find records (resolve, look-ups), never
     * the text of an answer.
     */
    static List<String> ids(Transaction tx, String query, Map<String, Object> params) {
        List<String> ids = new ArrayList<>();
        try (Result result = tx.execute(query, params)) {
            result.forEachRemaining(row -> ids.add((String) row.get("id")));
        }
        return ids;
    }
}
