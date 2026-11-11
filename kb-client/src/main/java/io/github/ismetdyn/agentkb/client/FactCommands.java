package io.github.ismetdyn.agentkb.client;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb fact set|close} and {@code kb rel add|close} (docs/CLIENTS.md §2, plan.md H6): the
 * user's facts and relations. Nothing is deleted: a changed fact and a closed one stay as history
 * on the server (invariant 5). Exit codes 0, 1 refused or bad input, 3 server unreachable.
 */
final class FactCommands {

    private FactCommands() {}

    @Command(
            name = "fact",
            description = "Set or close a fact.",
            subcommands = {Fact.Set.class, Fact.Close.class})
    static final class Fact {

        private Fact() {}

        @Command(
                name = "set",
                description = "Set a fact; a different current value of it is closed.")
        static final class Set implements Callable<Integer> {

            @Parameters(index = "0", paramLabel = "<id>", description = "The entity.")
            String id;

            @Parameters(index = "1", paramLabel = "<key>", description = "The key, e.g. ip.")
            String key;

            @Parameters(index = "2", paramLabel = "<value>", description = "The value.")
            String value;

            @Option(names = "--env", paramLabel = "<env>", description = "Its environment.")
            String env;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                AnswerText.written(
                        spec.commandLine().getOut(),
                        KbApi.fromEnvironment(JsonApi.REQUEST_TIMEOUT)
                                .setFact(id, key, value, env));
                return 0;
            }
        }

        @Command(name = "close", description = "Close a fact; it stays as history.")
        static final class Close implements Callable<Integer> {

            @Parameters(paramLabel = "<factId>", description = "The fact's id.")
            String factId;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                return EntityCommands.post(
                        spec,
                        "/api/v1/facts/" + RequestText.segment(factId) + "/close",
                        "closed fact " + factId);
            }
        }
    }

    @Command(
            name = "rel",
            description = "Add or close a relation.",
            subcommands = {Rel.Add.class, Rel.Close.class})
    static final class Rel {

        private Rel() {}

        @Command(name = "add", description = "Add a relation from one entity to another.")
        static final class Add implements Callable<Integer> {

            @Parameters(index = "0", paramLabel = "<id>", description = "Where it starts.")
            String id;

            @Parameters(index = "1", paramLabel = "<type>", description = "Its type, e.g. RUNS.")
            String type;

            @Parameters(index = "2", paramLabel = "<target>", description = "Where it ends.")
            String target;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                AnswerText.written(
                        spec.commandLine().getOut(),
                        KbApi.fromEnvironment(JsonApi.REQUEST_TIMEOUT)
                                .addRelation(id, type, target));
                return 0;
            }
        }

        @Command(name = "close", description = "Close a relation; it stays as history.")
        static final class Close implements Callable<Integer> {

            @Parameters(paramLabel = "<relId>", description = "The relation's id.")
            String relId;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                return EntityCommands.post(
                        spec,
                        "/api/v1/relations/" + RequestText.segment(relId) + "/close",
                        "closed relation " + relId);
            }
        }
    }
}
