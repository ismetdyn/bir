package io.github.ismetdyn.agentkb.client.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The records and keys the TuiState tests share; a ready state as after start. */
final class TuiSamples {

    private TuiSamples() {}

    static final String APP = "ornek-app-01";

    static final String DB = "ornek-db";

    static final SchemaAnswer SCHEMA =
            new SchemaAnswer(
                    List.of(
                            new SchemaAnswer.EntityType("server", "Server", true),
                            new SchemaAnswer.EntityType("database", "Database", true)),
                    List.of(
                            new SchemaAnswer.FactKey("ip", true, "ip", List.of(), null, false),
                            new SchemaAnswer.FactKey(
                                    "engine",
                                    false,
                                    null,
                                    List.of("oracle", "postgresql"),
                                    null,
                                    false),
                            new SchemaAnswer.FactKey("env", false, null, List.of(), null, false)),
                    List.of(
                            new SchemaAnswer.RelationType("RUNS", "runs it", List.of("HOSTS")),
                            new SchemaAnswer.RelationType("PART_OF", "part of it", List.of())));

    static final EntityListAnswer LIST =
            new EntityListAnswer(
                    List.of(
                            new EntityListAnswer.Entity(
                                    APP, "Server", "current", "app", "2026-11-10T08:00:00Z"),
                            new EntityListAnswer.Entity(
                                    DB, "Database", "archived", null, "2026-11-09T08:00:00Z")),
                    0);

    static EntityAnswer entity(String id, String status) {
        return new EntityAnswer(
                id,
                "Server",
                status,
                "app server",
                List.of("app01"),
                List.of(
                        new EntityAnswer.Fact(
                                "F1",
                                "ip",
                                "192.0.2.21",
                                "prod",
                                "user",
                                "ok",
                                "2026-05-10T09:00:00Z",
                                "2026-05-10T09:00:00Z",
                                null,
                                null,
                                null),
                        new EntityAnswer.Fact(
                                "F0",
                                "ip",
                                "192.0.2.20",
                                "prod",
                                "scan",
                                "ok",
                                "2026-01-10T09:00:00Z",
                                "2026-01-10T09:00:00Z",
                                "2026-05-10T09:00:00Z",
                                null,
                                null)),
                List.of(
                        new EntityAnswer.Relation(
                                "R1",
                                "BEHIND",
                                true,
                                "ornek-lb",
                                null,
                                "user",
                                "2026-05-10T09:00:00Z",
                                "2026-05-10T09:00:00Z",
                                "2026-06-10T09:00:00Z")),
                List.of(
                        new EntityAnswer.Secret(
                                APP + "/prod/deploy-key",
                                "deploy-key",
                                "prod",
                                "kb:secret/" + APP + "/prod/deploy-key")));
    }

    /** A state as after start: schema, the browse list and the first record's detail. */
    static TuiState ready() {
        TuiState state = new TuiState();
        long seq = loadList(state.start()).seq();
        state.schemaLoaded(SCHEMA);
        List<Intent> open = state.listLoaded(seq, LIST);
        assertThat(open).containsExactly(new Intent.Open(APP));
        state.entityLoaded(APP, entity(APP, "current"));
        return state;
    }

    static Intent.LoadList loadList(List<Intent> intents) {
        return intents.stream()
                .filter(Intent.LoadList.class::isInstance)
                .map(Intent.LoadList.class::cast)
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no LoadList in " + intents));
    }

    static KeyEvent key(char c) {
        return KeyEvent.ofChar(c);
    }

    static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    static List<Intent> type(TuiState state, String text) {
        List<Intent> intents = new ArrayList<>();
        text.codePoints().forEach(c -> intents.addAll(state.onKey(KeyEvent.ofChar(c))));
        return intents;
    }

    /** A file of fixtures/api/, the contract the server writes. */
    static String contract(String file) throws IOException {
        return Files.readString(Path.of("..", "fixtures", "api", file), StandardCharsets.UTF_8);
    }
}
