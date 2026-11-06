package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The client half of the kb-api contract (ADR-0025 decision 6, plan.md H2): Json.java maps each
 * file under fixtures/api/ to a record equal to the one the server writes as that file (kb-server
 * ApiContractTest lists the same pairs). Jackson is not used: it made native kb 55.8 MB. A field
 * the record does not know is ignored at every depth; a field the record needs that is missing,
 * null or of another type is a KbException with exit 1, never a NullPointerException or a default.
 */
class ApiContractTest {

    private static final Path CONTRACT = Path.of("..", "fixtures", "api");
    // The record components that are @Nullable, as "file:field"; every other field is required.
    private static final Set<String> OPTIONAL =
            Set.of("sync-answer.json:commit", "sync-answer-no-commit.json:commit");

    static Stream<Arguments> samples() {
        return Stream.of(
                arguments(
                        "revert-answer.json",
                        RevertAnswer.class,
                        new RevertAnswer("01KBTESTBATCH0000000000000Z", 7, 2, 1)),
                arguments(
                        "restore-answer.json",
                        RestoreAnswer.class,
                        new RestoreAnswer(140, 212, 3, 5, 1, 4)),
                arguments(
                        "sync-answer.json",
                        SyncAnswer.class,
                        new SyncAnswer(
                                "3f9a1c0e5b7d2468ace013579bdf2468ace01357", true, 140, 212, 2)),
                arguments(
                        "sync-answer-no-commit.json",
                        SyncAnswer.class,
                        new SyncAnswer(null, false, 141, 213, 0)),
                arguments(
                        "check-answer.json",
                        CheckAnswer.class,
                        new CheckAnswer(
                                false,
                                List.of(
                                        new CheckAnswer.Problem(
                                                "orphan-edge",
                                                "01KBTESTEDGE",
                                                "points to missing node kbtest-ğhost-İ \"quoted\""
                                                        + " \\ /"),
                                        new CheckAnswer.Problem(
                                                "git-fsck",
                                                "repo",
                                                "error: object file is empty")))),
                arguments(
                        "check-answer-clean.json",
                        CheckAnswer.class,
                        new CheckAnswer(true, List.of())),
                arguments(
                        "memory-import-answer.json",
                        MemoryImportAnswer.class,
                        new MemoryImportAnswer(4, 3, 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void the_contract_file_maps_to_an_equal_record(
            String file, Class<? extends Record> type, Record expected) throws IOException {
        assertThat(Json.read(contract(file), type)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void unknown_fields_at_every_depth_are_ignored(
            String file, Class<? extends Record> type, Record expected) throws IOException {
        String withExtras = JsonTree.write(JsonTree.withUnknownFields(Json.parse(contract(file))));

        assertThat(Json.read(withExtras, type)).isEqualTo(expected);
    }

    /**
     * Three cases per required field of every contract file, nested problem fields included: the
     * field missing, null, and of another type (a number where text belongs, text where a number,
     * boolean or list belongs).
     */
    static Stream<Arguments> brokenFields() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        for (Arguments sample : samples().toList()) {
            String file = (String) sample.get()[0];
            Object type = sample.get()[1];
            Object parsed = Json.parse(contract(file));
            for (List<Object> path : JsonTree.objectFields(parsed)) {
                if (OPTIONAL.contains(file + ":" + path.getLast())) {
                    continue;
                }
                Object wrong =
                        JsonTree.at(parsed, path) instanceof String
                                ? BigDecimal.valueOf(3)
                                : "kbtest-wrong-type";
                cases.add(
                        arguments(
                                file + " without " + path,
                                type,
                                JsonTree.write(JsonTree.without(parsed, path))));
                cases.add(
                        arguments(
                                file + " null " + path,
                                type,
                                JsonTree.write(JsonTree.with(parsed, path, null))));
                cases.add(
                        arguments(
                                file + " wrong type " + path,
                                type,
                                JsonTree.write(JsonTree.with(parsed, path, wrong))));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenFields")
    void a_broken_required_field_is_a_failure_with_exit_one(
            String name, Class<? extends Record> type, String json) {
        assertRefused(json, type);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void an_answer_that_is_no_object_is_a_failure_with_exit_one(
            String file, Class<? extends Record> type) {
        assertRefused("[]", type);
        assertRefused("\"text\"", type);
        assertRefused("{\"unclosed\": ", type);
    }

    private static void assertRefused(String json, Class<? extends Record> type) {
        KbException refused = catchThrowableOfType(KbException.class, () -> Json.read(json, type));

        assertThat(refused).as("%s as %s", json, type.getSimpleName()).isNotNull();
        assertThat(refused.exitCode()).isEqualTo(Kb.FAILED);
        assertThat(refused.getMessage()).isNotBlank();
    }

    private static String contract(String file) throws IOException {
        return Files.readString(CONTRACT.resolve(file), StandardCharsets.UTF_8);
    }
}
