package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import java.io.PrintWriter;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb search}, {@code kb stale}, {@code kb show} and {@code kb cypher} (docs/CLIENTS.md §2,
 * plan.md H6): one GET or POST each; the server searches, renders and guards the query. An option
 * left out is not sent, so the server's default and limit hold. Exit codes 0, 1 refused or bad
 * input, 3 server unreachable.
 */
final class ReadCommands {

    private ReadCommands() {}

    @Command(
            name = "search",
            aliases = "find",
            description = "Search the records: current first, then history, then raw signals.")
    static final class Search implements Callable<Integer> {

        @Parameters(arity = "1..*", paramLabel = "<word>", description = "The query.")
        List<String> words;

        @Option(names = "--type", paramLabel = "<type>", description = "Only this entity type.")
        String type;

        @Option(names = "--env", paramLabel = "<env>", description = "Only this environment.")
        String env;

        @Option(
                names = "--limit",
                paramLabel = "<n>",
                description = "At most n hits; server default if left out.")
        Integer limit;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String path =
                    RequestText.query(
                            "/api/v1/search",
                            "q",
                            String.join(" ", words),
                            "type",
                            type,
                            "env",
                            env,
                            "limit",
                            limit);
            SearchAnswer answer =
                    Json.toRecord(JsonApi.fromEnvironment().get(path), SearchAnswer.class);
            AnswerText.hits(spec.commandLine().getOut(), answer, "no matches");
            return 0;
        }
    }

    @Command(name = "stale", description = "List the records not seen for a while.")
    static final class Stale implements Callable<Integer> {

        @Option(
                names = "--days",
                paramLabel = "<days>",
                description = "Not seen for this many days; server default if left out.")
        Integer days;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            SearchAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .get(RequestText.query("/api/v1/stale", "days", days)),
                            SearchAnswer.class);
            AnswerText.hits(spec.commandLine().getOut(), answer, "nothing stale");
            return 0;
        }
    }

    @Command(
            name = "show",
            description =
                    "Print an entity as the server renders it (Markdown), or its JSON view with the"
                            + " fact and relation ids.")
    static final class Show implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "The entity's id or alias.")
        String id;

        @Option(
                names = "--json",
                description = "The JSON view: the ids kb fact close and kb rel close take.")
        boolean json;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String path = "/api/v1/entities/" + RequestText.segment(id);
            String text =
                    JsonApi.fromEnvironment()
                            .text(path, json ? "application/json" : "text/markdown");
            if (json) {
                // Read before printing, as every answer: a view without its fields is exit 1.
                Json.read(text, EntityAnswer.class);
            }
            PrintWriter out = spec.commandLine().getOut();
            out.print(text);
            if (!text.endsWith("\n")) {
                out.println();
            }
            out.flush();
            return 0;
        }
    }

    @Command(
            name = "cypher",
            description =
                    "Run a read-only Cypher query; columns and rows tab-separated, with \\\\, \\t"
                            + " and \\n for a backslash, tab and line break in a cell.")
    static final class Cypher implements Callable<Integer> {

        @Parameters(paramLabel = "<query>", description = "One read-only query, quoted.")
        String query;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            CypherAnswer answer =
                    Json.toRecord(
                            JsonApi.fromEnvironment()
                                    .post(
                                            "/api/v1/cypher",
                                            "{\"query\": " + RequestText.quote(query) + "}"),
                            CypherAnswer.class);
            PrintWriter out = spec.commandLine().getOut();
            out.println(row(answer.columns()));
            for (CypherAnswer.Row row : answer.rows()) {
                out.println(row(row.cells()));
            }
            out.flush();
            if (answer.truncated()) {
                // stderr: stdout holds the table only, for a pipe.
                spec.commandLine()
                        .getErr()
                        .println(
                                "kb: truncated; the server left out rows or characters past its limit");
            }
            return 0;
        }

        /**
         * One TSV line: a backslash, tab or line break in a cell is escaped, so a cell cannot add a
         * column or a row (CWE-74).
         */
        private static String row(List<String> cells) {
            StringJoiner line = new StringJoiner("\t");
            for (String cell : cells) {
                line.add(cell.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"));
            }
            return line.toString();
        }
    }
}
