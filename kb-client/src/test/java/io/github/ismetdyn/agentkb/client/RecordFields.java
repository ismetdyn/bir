package io.github.ismetdyn.agentkb.client;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which field of a contract file the kb-api record marks @Nullable, read from the record itself
 * (reflection is fine in a test; kb does not use it, ADR-0025 decision 6), so the contract tests
 * cannot drift from the records.
 */
final class RecordFields {

    private RecordFields() {}

    /**
     * Whether the record component at {@code path} (keys and list indexes, as JsonTree gives) under
     * {@code type} is @Nullable; a list's element type is the record its objects map to.
     */
    static boolean isNullable(Class<?> type, List<Object> path) {
        Class<?> current = type;
        RecordComponent component = null;
        for (Object step : path) {
            if (step instanceof Integer) {
                continue;
            }
            component = component(current, (String) step);
            Type generic = component.getGenericType();
            current =
                    generic instanceof ParameterizedType list
                                    && list.getActualTypeArguments()[0] instanceof Class<?> element
                            ? element
                            : component.getType();
        }
        if (component == null) {
            throw new IllegalArgumentException("no field in " + path);
        }
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }

    private static RecordComponent component(Class<?> type, String name) {
        return Arrays.stream(type.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException(type.getName() + " has no " + name));
    }
}
