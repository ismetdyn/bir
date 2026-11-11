package io.github.ismetdyn.agentkb.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Edits of what {@link Json#parse} returns (maps, lists, strings, BigDecimal, Boolean, null) and
 * its JSON text, so a contract test can break one field of a contract file at a time. A path is the
 * keys and list indexes down to a field.
 */
final class JsonTree {

    private JsonTree() {}

    /** The path of every object field, depth first. */
    static List<List<Object>> objectFields(Object value) {
        return objectFields(value, List.of());
    }

    static Object at(Object value, List<Object> path) {
        Object current = value;
        for (Object step : path) {
            current =
                    current instanceof Map<?, ?> map
                            ? map.get(step)
                            : ((List<?>) current).get((Integer) step);
        }
        return current;
    }

    /** A copy of {@code value} without the field at {@code path}. */
    static Object without(Object value, List<Object> path) {
        return edit(value, path, null, true);
    }

    /** A copy of {@code value} with the field at {@code path} set to {@code to}. */
    static Object with(Object value, List<Object> path, Object to) {
        return edit(value, path, to, false);
    }

    /** {@code value} with an unknown text, object and null field in every object. */
    static Object withUnknownFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, withUnknownFields(item)));
            copy.put("addedLaterText", "kbtest");
            copy.put("addedLaterObject", Map.of("nested", List.of(BigDecimal.ONE, "x")));
            copy.put("addedLaterNull", null);
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonTree::withUnknownFields).toList();
        }
        return value;
    }

    static String write(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> RequestText.quote(text);
            case Map<?, ?> map -> {
                StringJoiner out = new StringJoiner(", ", "{", "}");
                map.forEach(
                        (key, item) ->
                                out.add(RequestText.quote((String) key) + ": " + write(item)));
                yield out.toString();
            }
            case List<?> list -> {
                StringJoiner out = new StringJoiner(", ", "[", "]");
                list.forEach(item -> out.add(write(item)));
                yield out.toString();
            }
            default -> value.toString();
        };
    }

    private static List<List<Object>> objectFields(Object value, List<Object> at) {
        List<List<Object>> paths = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (key, item) -> {
                        List<Object> path = append(at, key);
                        paths.add(path);
                        paths.addAll(objectFields(item, path));
                    });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                paths.addAll(objectFields(list.get(i), append(at, i)));
            }
        }
        return paths;
    }

    private static List<Object> append(List<Object> path, Object step) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(step);
        return List.copyOf(longer);
    }

    private static Object edit(Object value, List<Object> path, Object to, boolean remove) {
        Object step = path.getFirst();
        List<Object> rest = path.subList(1, path.size());
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            if (!rest.isEmpty()) {
                copy.put(step, edit(map.get(step), rest, to, remove));
            } else if (remove) {
                copy.remove(step);
            } else {
                copy.put(step, to);
            }
            return copy;
        }
        List<Object> copy = new ArrayList<>((List<?>) value);
        int index = (Integer) step;
        copy.set(index, edit(copy.get(index), rest, to, remove));
        return copy;
    }
}
