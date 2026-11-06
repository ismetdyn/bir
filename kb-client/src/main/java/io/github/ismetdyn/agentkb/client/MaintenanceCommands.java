package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code kb revert --batch <id>}, {@code kb restore [--at <commit>]}, {@code kb sync} and {@code kb
 * check} (docs/CLIENTS.md §2, ADR-0022 decision 3): each one REST call to the local server, which
 * does the work. Each answer is read into its kb-api record before anything is printed, so an
 * answer without a field it needs is exit 1, not a "null" in the output. Exit codes: 0 success, 1
 * error, problems found or such an answer, 3 server unreachable.
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
            RevertAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .post(
                                            "/api/v1/revert",
                                            "{\"batch\": " + JsonApi.quote(batch) + "}"),
                            RevertAnswer.class);
            spec.commandLine()
                    .getOut()
                    .println(
                            "reverted batch "
                                    + answer.batch()
                                    + ": deleted "
                                    + answer.deleted()
                                    + " records, reopened "
                                    + answer.reopened()
                                    + "; secret values deleted for good (they live only on this"
                                    + " machine): "
                                    + answer.deletedSecretValues());
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
            RestoreAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().post("/api/v1/restore", body),
                            RestoreAnswer.class);
            spec.commandLine()
                    .getOut()
                    .println(
                            "restored "
                                    + answer.nodes()
                                    + " nodes and "
                                    + answer.edges()
                                    + " edges; kept "
                                    + answer.keptSecrets()
                                    + " local secrets and "
                                    + answer.keptSignals()
                                    + " signals; dropped "
                                    + answer.droppedEdges()
                                    + " signal edges to records the snapshot lacks; ignored "
                                    + answer.droppedSecretValues()
                                    + " secret values the snapshot should not carry");
            return 0;
        }
    }

    @Command(
            name = "sync",
            description =
                    "Merge the remote's snapshot into the graph, commit the snapshot and push it;"
                            + " never force-pushes.")
    static final class Sync implements Callable<Integer> {

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            SyncAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().post("/api/v1/sync", "{}"), SyncAnswer.class);
            String commit = answer.commit();
            spec.commandLine()
                    .getOut()
                    .println(
                            (commit == null
                                            ? "no new commit (nothing changed)"
                                            : "commit " + commit)
                                    + (answer.pushed()
                                            ? ", pushed to the remote"
                                            : ", kept in the local repository only (no remote)")
                                    + "; "
                                    + answer.nodes()
                                    + " nodes and "
                                    + answer.edges()
                                    + " edges written, "
                                    + answer.conflicts()
                                    + " conflicts found (each has a conflict signal)");
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
            CheckAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().get("/api/v1/check"), CheckAnswer.class);
            PrintWriter out = spec.commandLine().getOut();
            for (CheckAnswer.Problem problem : answer.problems()) {
                out.println(problem.kind() + " " + problem.id() + ": " + problem.detail());
            }
            if (answer.clean()) {
                out.println("clean");
                return 0;
            }
            out.println(answer.problems().size() + " problems");
            return Kb.FAILED;
        }
    }
}
