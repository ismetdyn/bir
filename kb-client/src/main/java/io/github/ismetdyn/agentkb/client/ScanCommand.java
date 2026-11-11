package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.ScanAnswer;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb scan <path>} (docs/CLIENTS.md §2, plan.md H6): {@code POST /api/v1/scan}. The path is
 * made absolute here, against the directory kb runs in, because the server runs elsewhere; the
 * server alone decides whether it lies under its allowed roots. Secrets come back as placeholders;
 * the values stay on this machine (invariant 2). Exit codes 0, 1 refused or bad input, 3 server
 * unreachable.
 */
@Command(name = "scan", description = "Scan a project directory into the graph.")
final class ScanCommand implements Callable<Integer> {

    // Text, not Path: picocli converts a Path by reflection, which native kb would need configured.
    @Parameters(paramLabel = "<path>", description = "The directory; relative to this one.")
    String path;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        String absolute = Path.of(path).toAbsolutePath().toString();
        String body = "{\"path\": " + RequestText.quote(absolute) + "}";
        ScanAnswer answer =
                Json.toRecord(
                        JsonApi.fromEnvironment().post("/api/v1/scan", body), ScanAnswer.class);
        PrintWriter out = spec.commandLine().getOut();
        out.println(
                AnswerText.oneLine(
                        "scanned "
                                + answer.project()
                                + " ("
                                + answer.repo()
                                + "): "
                                + answer.files()
                                + " files; batch "
                                + answer.batch()));
        AnswerText.items(out, "new entities", answer.newEntities());
        out.println(
                "facts: "
                        + answer.added()
                        + " added, "
                        + answer.refreshed()
                        + " refreshed, "
                        + answer.closed()
                        + " closed, "
                        + answer.conflicts()
                        + " conflicts");
        AnswerText.items(out, "secrets (values stay on this machine)", answer.secrets());
        AnswerText.items(out, "findings", answer.findings());
        AnswerText.items(out, "skipped", answer.skipped());
        return 0;
    }
}
