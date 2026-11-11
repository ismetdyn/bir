package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb add}, {@code kb edit}, {@code kb archive} and {@code kb unarchive} (docs/CLIENTS.md §2,
 * plan.md H6): the user's writes to an entity, each one batch on the server, which checks the type,
 * the names and whether the change is allowed. Exit codes 0, 1 refused or bad input, 3 server
 * unreachable.
 */
final class EntityCommands {

    private static final String ENTITIES = "/api/v1/entities/";

    private EntityCommands() {}

    @Command(name = "add", description = "Add an entity; it needs no facts yet.")
    static final class Add implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<type>", description = "Its type, e.g. Server.")
        String type;

        @Parameters(index = "1", paramLabel = "<id>", description = "Its id.")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String body =
                    "{\"id\": "
                            + RequestText.quote(id)
                            + ", \"type\": "
                            + RequestText.quote(type)
                            + "}";
            WriteAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().post("/api/v1/entities", body),
                            WriteAnswer.class);
            AnswerText.written(spec.commandLine().getOut(), answer);
            return 0;
        }
    }

    @Command(name = "edit", description = "Set the aliases or the summary of an entity.")
    static final class Edit implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The entity.")
        String id;

        @Option(
                names = "--alias",
                paramLabel = "<name>",
                description = "A name it is known by; repeat it. The given ones replace the list.")
        List<String> aliases;

        @Option(names = "--clear-aliases", description = "Remove every alias; not with --alias.")
        boolean clearAliases;

        @Option(names = "--summary", paramLabel = "<text>", description = "Its summary.")
        String summary;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            if (clearAliases && aliases != null) {
                throw new KbException("give --alias or --clear-aliases, not both");
            }
            List<String> given = clearAliases ? List.of() : aliases;
            StringJoiner body = new StringJoiner(", ", "{", "}");
            if (given != null) {
                StringJoiner names = new StringJoiner(", ", "[", "]");
                given.forEach(alias -> names.add(RequestText.quote(alias)));
                body.add("\"aliases\": " + names);
            }
            if (summary != null) {
                body.add("\"summary\": " + RequestText.quote(summary));
            }
            EditAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .patch(ENTITIES + RequestText.segment(id), body.toString()),
                            EditAnswer.class);
            AnswerText.edited(spec.commandLine().getOut(), "edited " + id, answer);
            return 0;
        }
    }

    @Command(name = "archive", description = "Archive an entity; kb unarchive brings it back.")
    static final class Archive implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The entity.")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            return post(spec, ENTITIES + RequestText.segment(id) + "/archive", "archived " + id);
        }
    }

    @Command(name = "unarchive", description = "Bring an archived entity back.")
    static final class Unarchive implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The entity.")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            return post(
                    spec, ENTITIES + RequestText.segment(id) + "/unarchive", "unarchived " + id);
        }
    }

    /**
     * POSTs an edit without a body to {@code path} and prints {@code done} with its batch; exit 0.
     * The fact, relation and review commands use it too.
     */
    static int post(CommandSpec spec, String path, String done) {
        EditAnswer answer =
                Json.toRecord(JsonApi.fromEnvironment().post(path, "{}"), EditAnswer.class);
        AnswerText.edited(spec.commandLine().getOut(), done, answer);
        return 0;
    }
}
