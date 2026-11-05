package io.github.ismetdyn.agentkb.client;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code kb revert --batch <id>}, {@code kb restore [--at <commit>]} and {@code kb check}
 * (docs/CLIENTS.md §2, ADR-0022 decision 3): each one REST call to the local server, which does the
 * work. Exit codes: 0 success, 1 error or problems found, 3 server unreachable.
 */
final class MaintenanceCommands {

    private MaintenanceCommands() {}

    @Command(
            name = "revert",
            description = "Undo one batch: delete what it opened, reopen what it closed.")
    static final class Revert implements Callable<Integer> {

        @Option(names = "--batch", required = true, paramLabel = "<id>", description = "The batch.")
        String batch;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            Map<?, ?> answer =
                    JsonApi.fromEnvironment()
                            .post("/api/v1/revert", "{\"batch\": " + JsonApi.quote(batch) + "}");
            spec.commandLine()
                    .getOut()
                    .println(
                            "reverted batch "
                                    + answer.get("batch")
                                    + ": deleted "
                                    + answer.get("deleted")
                                    + " records, reopened "
                                    + answer.get("reopened")
                                    + "; secret values deleted for good (they live only on this"
                                    + " machine): "
                                    + answer.get("deletedSecretValues"));
            return 0;
        }
    }

    @Command(
            name = "restore",
            description =
                    "Rebuild the graph from the snapshot of a commit of the knowledge repository;"
                            + " secret values and signals stay.")
    static final class Restore implements Callable<Integer> {

        @Option(names = "--at", paramLabel = "<commit>", description = "The commit; HEAD if none.")
        String at;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String body = at == null ? "{}" : "{\"at\": " + JsonApi.quote(at) + "}";
            Map<?, ?> answer = JsonApi.fromEnvironment().post("/api/v1/restore", body);
            spec.commandLine()
                    .getOut()
                    .println(
                            "restored "
                                    + answer.get("nodes")
                                    + " nodes and "
                                    + answer.get("edges")
                                    + " edges; kept "
                                    + answer.get("keptSecrets")
                                    + " local secrets and "
                                    + answer.get("keptSignals")
                                    + " signals; dropped "
                                    + answer.get("droppedEdges")
                                    + " signal edges to records the snapshot lacks; ignored "
                                    + answer.get("droppedSecretValues")
                                    + " secret values the snapshot should not carry");
            return 0;
        }
    }

    @Command(
            name = "check",
            description =
                    "List consistency problems of the graph, the snapshot files and the"
                            + " repository.")
    static final class Check implements Callable<Integer> {

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            Map<?, ?> answer = JsonApi.fromEnvironment().get("/api/v1/check");
            PrintWriter out = spec.commandLine().getOut();
            List<?> problems = answer.get("problems") instanceof List<?> list ? list : List.of();
            for (Object item : problems) {
                if (item instanceof Map<?, ?> problem) {
                    out.println(
                            problem.get("kind")
                                    + " "
                                    + problem.get("id")
                                    + ": "
                                    + problem.get("detail"));
                }
            }
            if (Boolean.TRUE.equals(answer.get("clean"))) {
                out.println("clean");
                return 0;
            }
            out.println(problems.size() + " problems");
            return Kb.FAILED;
        }
    }
}
