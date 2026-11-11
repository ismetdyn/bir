package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The other half of ApiContractTest's required fields: every field the kb-api record
 * marks @Nullable (RecordFields) Json.java reads as null when the server sends null, so the record
 * and the reader agree in both directions.
 */
class NullableFieldsTest {

    static Stream<Arguments> nullableFields() {
        List<Arguments> cases = new ArrayList<>();
        for (Arguments sample : ApiContractTest.samples().toList()) {
            String file = (String) sample.get()[0];
            Class<?> type = (Class<?>) sample.get()[1];
            Object parsed = Json.parse(KbCommandCases.contract(file));
            for (List<Object> path : JsonTree.objectFields(parsed)) {
                if (RecordFields.isNullable(type, path)) {
                    cases.add(
                            arguments(
                                    file + " null " + path,
                                    type,
                                    JsonTree.write(JsonTree.with(parsed, path, null))));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableFields")
    void a_nullable_field_may_be_null(String name, Class<? extends Record> type, String json) {
        assertThat(Json.read(json, type)).isNotNull();
    }
}
