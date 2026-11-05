package io.github.ismetdyn.agentkb.server.write;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Cypher row values as plain, comparable Java values: maps by key, arrays as lists. */
final class GraphRows {

    private GraphRows() {}

    /** A nested {@code properties(…)} column of a row, typed for AssertJ map assertions. */
    static Map<String, Object> props(Object column) {
        Map<String, Object> map = new TreeMap<>();
        ((Map<?, ?>) column).forEach((key, item) -> map.put((String) key, plain(item)));
        return map;
    }

    static Map<String, Object> without(Object column, Set<String> skip) {
        Map<String, Object> kept = props(column);
        kept.keySet().removeAll(skip);
        return kept;
    }

    static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            return props(map);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                list.add(Array.get(value, i));
            }
            return list;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GraphRows::plain).toList();
        }
        return value;
    }
}
