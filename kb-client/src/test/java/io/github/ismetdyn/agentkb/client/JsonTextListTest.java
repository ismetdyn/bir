package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * plan.md H2 for the lists of text the H6 records hold (ids, aliases, cells): every element is
 * text. A number, null or object in such a list is a KbException with exit 1, as a missing field is
 * (ApiContractTest), never a default or a ClassCastException.
 */
class JsonTextListTest {

    static Stream<String> listsWithAnotherElement() {
        String start =
                "{\"entity\": \"kbtest\", \"created\": true, \"batch\": \"01KBTEST\","
                        + " \"refreshed\": [], \"closed\": [], \"conflicts\": [], \"added\": ";
        return Stream.of(start + "[\"01KBTESTFACT\", 3]}", start + "[null]}", start + "[{}]}");
    }

    @ParameterizedTest
    @MethodSource("listsWithAnotherElement")
    void a_list_of_text_with_another_element_is_a_failure_with_exit_one(String json) {
        KbException refused =
                catchThrowableOfType(KbException.class, () -> Json.read(json, WriteAnswer.class));

        assertThat(refused).as(json).isNotNull();
        assertThat(refused.exitCode()).isEqualTo(Kb.FAILED);
        assertThat(refused.getMessage()).contains("added");
    }
}
