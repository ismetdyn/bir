package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.DoctorAnswer;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code kb doctor} (docs/CLIENTS.md §2, plan.md H7): {@code GET /api/v1/doctor}; the server runs
 * the checks (git, git-lfs, repo, lfs-filter, data-dir). One line per check, the hint after a
 * failed one. Exit codes 0 every check passed, 1 one failed or the server refused, 3 server
 * unreachable.
 */
@Command(name = "doctor", description = "Check git, git-lfs, the repository and the data dir.")
final class DoctorCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        DoctorAnswer answer =
                Json.toRecord(JsonApi.fromEnvironment().get("/api/v1/doctor"), DoctorAnswer.class);
        PrintWriter out = spec.commandLine().getOut();
        boolean healthy = answer.ok();
        for (DoctorAnswer.Check check : answer.checks()) {
            out.println(AnswerText.line(check.name(), check.ok() ? "ok" : "fail", check.hint()));
            // A failed check fails kb doctor even if the answer's ok says otherwise.
            healthy &= check.ok();
        }
        return healthy ? 0 : Kb.FAILED;
    }
}
