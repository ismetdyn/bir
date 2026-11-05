package io.github.ismetdyn.agentkb.server.graph;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

/**
 * The rows of a kb_cypher query as plain values, with no secret value in them (invariant 2, SPEC
 * §13.5 "sonuçlardan bu alan çıkarılır"). Nodes, relationships and maps lose {@code secret_value};
 * a string that holds a value, however the query built it, has it masked. Runs inside the query's
 * transaction, where nodes can still be read.
 */
final class CypherRows {

    private static final String SECRET_VALUE = "secret_value";
    private static final String MASK = "*****";
    private static final String SECRET_VALUES =
            "MATCH (s:Secret) WHERE s.secret_value IS NOT NULL RETURN s.secret_value AS v";

    private final List<String> secretValues;

    private CypherRows(List<String> secretValues) {
        this.secretValues = secretValues;
    }

    /** The filter for the secret values the graph holds now. */
    static CypherRows in(Transaction tx) {
        try (Result result = tx.execute(SECRET_VALUES)) {
            return new CypherRows(
                    result.stream()
                            .map(row -> String.valueOf(row.get("v")))
                            .filter(value -> !value.isEmpty())
                            .distinct()
                            .toList());
        }
    }

    /** At most {@code limit} rows, each in the column order of the query. */
    List<Map<String, Object>> rows(Result result, int limit) {
        List<String> columns = result.columns();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < limit && result.hasNext()) {
            Map<String, Object> row = result.next();
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String column : columns) {
                clean.put(redact(column), clean(row.get(column)));
            }
            rows.add(clean);
        }
        return rows;
    }

    private Object clean(Object value) {
        return switch (value) {
            case null -> null;
            case Node node -> map(node.getAllProperties());
            case Relationship relation -> relationship(relation);
            case Path path -> list(path);
            case Map<?, ?> map -> map(map);
            case Iterable<?> list -> list(list);
            case String text -> redact(text);
            default -> value.getClass().isArray() ? array(value) : value;
        };
    }

    private Map<String, Object> relationship(Relationship relation) {
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("type", relation.getType().name());
        clean.putAll(map(relation.getAllProperties()));
        return clean;
    }

    // Sorted, so a node reads the same in every answer.
    private Map<String, Object> map(Map<?, ?> map) {
        Map<String, Object> clean = new TreeMap<>();
        map.forEach(
                (key, value) -> {
                    String name = redact(String.valueOf(key));
                    if (!SECRET_VALUE.equalsIgnoreCase(name)) {
                        clean.put(name, clean(value));
                    }
                });
        return clean;
    }

    private List<Object> list(Iterable<?> values) {
        List<Object> clean = new ArrayList<>();
        values.forEach(value -> clean.add(clean(value)));
        return clean;
    }

    // A list property of a node comes as a Java array (String[], long[], …).
    private List<Object> array(Object array) {
        List<Object> clean = new ArrayList<>();
        for (int i = 0; i < Array.getLength(array); i++) {
            clean.add(clean(Array.get(array, i)));
        }
        return clean;
    }

    // Every occurrence of every value is found in the original text first, then each covered run
    // becomes one mask: replacing one value after another would leave the rest of a second value
    // readable when the two overlap or one holds the other.
    private String redact(String text) {
        boolean[] covered = new boolean[text.length()];
        boolean found = false;
        for (String secret : secretValues) {
            for (int at = text.indexOf(secret); at >= 0; at = text.indexOf(secret, at + 1)) {
                Arrays.fill(covered, at, at + secret.length(), true);
                found = true;
            }
        }
        if (!found) {
            return text;
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!covered[i]) {
                clean.append(text.charAt(i));
            } else if (i == 0 || !covered[i - 1]) {
                clean.append(MASK);
            }
        }
        return clean.toString();
    }
}
