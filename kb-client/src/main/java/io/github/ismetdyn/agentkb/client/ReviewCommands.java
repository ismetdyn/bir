package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb conflicts}, {@code kb resolve}, {@code kb proposals}, {@code kb accept}, {@code kb
 * reject} and {@code kb activity} (docs/CLIENTS.md §2, plan.md H6): what waits for the user and
 * what was written. The server checks a resolution, a source and a time; kb sends them as typed.
 * Exit codes 0, 1 refused or bad input, 3 server unreachable.
 */
final class ReviewCommands {

    private ReviewCommands() {}

    @Command(name = "conflicts", description = "List the values waiting as a conflict.")
    static final class Conflicts implements Callable<Integer> {

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            ConflictsAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().get("/api/v1/conflicts"),
                            ConflictsAnswer.class);
            PrintWriter out = spec.commandLine().getOut();
            if (answer.conflicts().isEmpty() && answer.more() == 0) {
                out.println("no conflicts");
            }
            for (ConflictsAnswer.Conflict conflict : answer.conflicts()) {
                out.println(
                        AnswerText.line(
                                conflict.id(),
                                conflict.entity()
                                        + " "
                                        + conflict.key()
                                        + " @"
                                        + conflict.env()
                                        + ": "
                                        + conflict.value()
                                        + " ("
                                        + conflict.source()
                                        + ", "
                                        + conflict.validFrom()
                                        + "); current: "
                                        + String.join(", ", conflict.current())));
            }
            AnswerText.more(out, answer.more());
            return 0;
        }
    }

    @Command(
            name = "resolve",
            description = "Resolve a conflict: take the new value or keep the old.")
    static final class Resolve implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<id>", description = "The conflict (kb conflicts).")
        String id;

        @Parameters(
                index = "1",
                paramLabel = "accept-new|keep-old",
                description = "accept-new or keep-old.")
        String resolution;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            EditAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .post(
                                            "/api/v1/conflicts/"
                                                    + RequestText.segment(id)
                                                    + "/resolve",
                                            "{\"resolution\": "
                                                    + RequestText.quote(resolution)
                                                    + "}"),
                            EditAnswer.class);
            AnswerText.edited(
                    spec.commandLine().getOut(),
                    "resolved conflict " + id + " (" + resolution + ")",
                    answer);
            return 0;
        }
    }

    @Command(name = "proposals", description = "List the proposals: open ones first.")
    static final class Proposals implements Callable<Integer> {

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            ProposalsAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment().get("/api/v1/proposals"),
                            ProposalsAnswer.class);
            PrintWriter out = spec.commandLine().getOut();
            if (answer.proposals().isEmpty() && answer.more() == 0) {
                out.println("no proposals");
            }
            for (ProposalsAnswer.Proposal proposal : answer.proposals()) {
                print(out, proposal);
            }
            AnswerText.more(out, answer.more());
            return 0;
        }

        /** A head line, then the content and what else there is, indented. */
        private static void print(PrintWriter out, ProposalsAnswer.Proposal proposal) {
            String target = proposal.target();
            out.println(
                    AnswerText.line(
                            proposal.id(),
                            proposal.kind(),
                            proposal.status(),
                            "by " + proposal.by(),
                            target == null ? null : "target " + target));
            AnswerText.indented(out, proposal.content());
            if (proposal.evidence() != null) {
                AnswerText.indented(out, "evidence: " + proposal.evidence());
            }
            if (proposal.reviewNote() != null) {
                AnswerText.indented(out, "note: " + proposal.reviewNote());
            }
        }
    }

    @Command(name = "accept", description = "Accept an open proposal; the server applies it.")
    static final class Accept implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The proposal (kb proposals).")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            return EntityCommands.post(
                    spec,
                    "/api/v1/proposals/" + RequestText.segment(id) + "/accept",
                    "accepted proposal " + id);
        }
    }

    @Command(name = "reject", description = "Reject an open proposal.")
    static final class Reject implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The proposal (kb proposals).")
        String id;

        @Option(names = "-m", paramLabel = "<note>", description = "Why; kept with the proposal.")
        String note;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            EditAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .post(
                                            "/api/v1/proposals/"
                                                    + RequestText.segment(id)
                                                    + "/reject",
                                            note == null
                                                    ? "{}"
                                                    : "{\"note\": "
                                                            + RequestText.quote(note)
                                                            + "}"),
                            EditAnswer.class);
            AnswerText.edited(spec.commandLine().getOut(), "rejected proposal " + id, answer);
            return 0;
        }
    }

    @Command(
            name = "activity",
            description = "List the latest batches, newest first; kb revert --batch undoes one.")
    static final class Activity implements Callable<Integer> {

        @Option(names = "--source", paramLabel = "<source>", description = "e.g. agent, scan.")
        String source;

        @Option(names = "--batch", paramLabel = "<id>", description = "Only this batch.")
        String batch;

        @Option(
                names = "--since",
                paramLabel = "<time>",
                description = "ISO-8601, e.g. 2026-11-01T00:00:00Z.")
        String since;

        @Option(
                names = "--limit",
                paramLabel = "<n>",
                description = "At most n; server default if left out.")
        Integer limit;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String path =
                    RequestText.query(
                            "/api/v1/activity",
                            "source",
                            source,
                            "batch",
                            batch,
                            "since",
                            since,
                            "limit",
                            limit);
            ActivityAnswer answer =
                    Json.toRecord(JsonApi.fromEnvironment().get(path), ActivityAnswer.class);
            PrintWriter out = spec.commandLine().getOut();
            if (answer.batches().isEmpty() && answer.more() == 0) {
                out.println("no activity");
            }
            for (ActivityAnswer.Batch written : answer.batches()) {
                out.println(
                        AnswerText.line(
                                written.batch(),
                                written.at(),
                                String.join(", ", written.sources()),
                                "opened "
                                        + written.opened()
                                        + ", closed "
                                        + written.closed()
                                        + ", revised "
                                        + written.revised(),
                                String.join(", ", written.entities())));
            }
            AnswerText.more(out, answer.more());
            return 0;
        }
    }
}
