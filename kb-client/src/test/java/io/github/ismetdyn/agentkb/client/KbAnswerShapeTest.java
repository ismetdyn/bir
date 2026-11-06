package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * plan.md H2: an answer that lacks a field its kb-api record needs, holds null there or holds the
 * wrong type is a failure kb names in one line, exit 1. It is never printed as "null", never a
 * NullPointerException ("kb: unexpected failure (…)") and never a half-printed line on stdout. A
 * field the record does not know is ignored (ApiContractTest checks the records themselves).
 */
class KbAnswerShapeTest {

    private static final String REVERT = "/api/v1/revert";
    private static final String RESTORE = "/api/v1/restore";
    private static final String SYNC = "/api/v1/sync";
    private static final String CHECK = "/api/v1/check";
    private static final String IMPORT = "/api/v1/signals/imported-memories";

    @TempDir Path workDir;
    @TempDir Path home;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    static Stream<Arguments> brokenAnswers() {
        return Stream.of(
                arguments(
                        "revert without deleted",
                        "POST",
                        REVERT,
                        "{\"batch\":\"01KBTEST\",\"reopened\":2,\"deletedSecretValues\":1}",
                        "revert --batch 01KBTEST"),
                arguments(
                        "revert with a null batch",
                        "POST",
                        REVERT,
                        "{\"batch\":null,\"deleted\":7,\"reopened\":2,\"deletedSecretValues\":1}",
                        "revert --batch 01KBTEST"),
                arguments(
                        "restore without keptSignals",
                        "POST",
                        RESTORE,
                        "{\"nodes\":1,\"edges\":2,\"keptSecrets\":3,\"droppedEdges\":4,"
                                + "\"droppedSecretValues\":5}",
                        "restore"),
                arguments(
                        "restore with a text count",
                        "POST",
                        RESTORE,
                        "{\"nodes\":\"many\",\"edges\":2,\"keptSecrets\":3,\"keptSignals\":4,"
                                + "\"droppedEdges\":5,\"droppedSecretValues\":6}",
                        "restore"),
                arguments(
                        "sync without pushed",
                        "POST",
                        SYNC,
                        "{\"commit\":null,\"nodes\":1,\"edges\":2,\"conflicts\":0}",
                        "sync"),
                arguments("check without clean", "GET", CHECK, "{\"problems\":[]}", "check"),
                arguments(
                        "check with a problem without kind",
                        "GET",
                        CHECK,
                        "{\"clean\":false,\"problems\":[{\"id\":\"01KBTESTEDGE\",\"detail\":\"d\"}]}",
                        "check"),
                arguments(
                        "check with problems that are no list",
                        "GET",
                        CHECK,
                        "{\"clean\":false,\"problems\":{}}",
                        "check"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenAnswers")
    void an_answer_without_a_required_field_exits_one_with_a_message(
            String name, String method, String path, String answer, String command) {
        server.answer(method, path, 200, answer);

        Result result = server.kb(workDir, command.split(" "));

        assertBrokenAnswer(result);
    }

    @Test
    void an_import_answer_without_opened_exits_one_with_a_message() throws IOException {
        Path memory = home.resolve(".codex/memories/kbtest.md");
        Files.createDirectories(memory.getParent());
        Files.writeString(memory, "kbtest memory");
        server.answer("POST", IMPORT, 200, "{\"received\":1,\"truncated\":0}");

        Result result =
                KbProcess.run(
                        server.url(),
                        Map.of("HOME", home.toString()),
                        "",
                        workDir,
                        "import-memories");

        assertBrokenAnswer(result);
    }

    /** The server may add a field before kb knows it (ADR-0025 decision 6). */
    @Test
    void an_unknown_field_is_ignored() {
        server.answer(
                "POST",
                REVERT,
                200,
                "{\"batch\":\"01KBTEST\",\"deleted\":7,\"reopened\":2,\"deletedSecretValues\":1,"
                        + "\"addedLater\":{\"nested\":[1,\"x\",null]}}");

        Result result = server.kb(workDir, "revert", "--batch", "01KBTEST");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("reverted batch 01KBTEST: deleted 7 records, reopened 2")
                .doesNotContain("addedLater");
    }

    private static void assertBrokenAnswer(Result result) {
        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(1);
        assertThat(result.stdout()).as("nothing half-printed").isEmpty();
        assertThat(result.stderr().strip())
                .startsWith("kb: ")
                .doesNotContain("unexpected failure", "Exception", "\n");
    }
}
