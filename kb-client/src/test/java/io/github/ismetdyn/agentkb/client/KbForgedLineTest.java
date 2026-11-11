package io.github.ismetdyn.agentkb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.ismetdyn.agentkb.client.KbProcess.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * H6 review (display spoofing): a line break in server text must not start a line that looks like a
 * record of its own. A one-line field (a conflict's value, a snippet, a secret's placeholder, an
 * activity's entities) is printed on one line; a multi-line field of a proposal (evidence, note)
 * has every further line indented, as its content has. The text itself stays visible.
 */
class KbForgedLineTest {

    // JSON string content: a line break, then what a record line of each list starts with.
    private static final String FORGED = "\\nkbtest-forged  Server  current  forged";

    @TempDir Path workDir;

    private ApiStub server;

    @BeforeEach
    void startServer() throws IOException {
        server = new ApiStub();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    static Stream<Arguments> forgedAnswers() {
        return Stream.of(
                arguments(
                        "conflict value",
                        "/api/v1/conflicts",
                        "{\"conflicts\":[{\"id\":\"01KBTESTC\",\"entity\":\"kbtest-a\",\"key\":\"ip\","
                                + "\"env\":\"prod\",\"value\":\"192.0.2.25"
                                + FORGED
                                + "\",\"source\":\"agent\",\"validFrom\":\"2026-10-28T15:00:00Z\","
                                + "\"current\":[\"192.0.2.21\"]}],\"more\":0}",
                        "conflicts"),
                arguments(
                        "proposal evidence",
                        "/api/v1/proposals",
                        proposal("\"evidence\":\"README.md" + FORGED + "\",\"reviewNote\":null"),
                        "proposals"),
                arguments(
                        "proposal note",
                        "/api/v1/proposals",
                        proposal("\"evidence\":null,\"reviewNote\":\"gerek yok" + FORGED + "\""),
                        "proposals"),
                arguments(
                        "search snippet",
                        "/api/v1/search",
                        "{\"hits\":[{\"id\":\"kbtest-a\",\"type\":\"Server\",\"tier\":\"current\","
                                + "\"archived\":false,\"snippet\":\"ip"
                                + FORGED
                                + "\"}],\"more\":0}",
                        "search kbtest"),
                arguments(
                        "secret placeholder",
                        "/api/v1/secrets",
                        "{\"secrets\":[{\"id\":\"kbtest-a/prod/x\",\"name\":\"x\",\"env\":\"prod\","
                                + "\"placeholder\":\"kb:secret/kbtest-a/prod/x"
                                + FORGED
                                + "\",\"hasValue\":true}],\"more\":0}",
                        "secret list"),
                arguments(
                        "activity entity",
                        "/api/v1/activity",
                        "{\"batches\":[{\"batch\":\"01KBTESTB\",\"at\":\"2026-11-10T08:00:00Z\","
                                + "\"sources\":[\"scan\"],\"opened\":1,\"closed\":0,\"revised\":0,"
                                + "\"entities\":[\"kbtest-a"
                                + FORGED
                                + "\"]}],\"more\":0}",
                        "activity"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgedAnswers")
    void a_line_break_in_server_text_starts_no_record_line(
            String name, String path, String answer, String command) {
        server.answer("GET", path, 200, answer);

        Result result = server.kb(workDir, command.split(" "));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("kbtest-forged");
        assertThat(result.stdout().lines()).noneMatch(line -> line.startsWith("kbtest-forged"));
    }

    private static String proposal(String evidenceAndNote) {
        return "{\"proposals\":[{\"id\":\"01KBTESTP\",\"kind\":\"archive\",\"status\":\"open\","
                + "\"by\":\"agent\",\"target\":null,\"content\":\"kapandı\","
                + evidenceAndNote
                + "}],\"more\":0}";
    }
}
