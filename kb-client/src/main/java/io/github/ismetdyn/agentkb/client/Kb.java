package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.client.tui.KbTuiCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code kb} command (docs/CLIENTS.md §2): a thin client of the local server's REST API; it
 * never opens the graph (ADR-0022 decision 3) and holds no business rule (ARCHITECTURE §1). Output
 * is UTF-8 whatever the locale says, so a value can be piped exactly. Exit codes: 0, 1 for an error
 * or a wrong command line, 3 when the server cannot be reached; kb run passes on its command's, and
 * kb hook always exits 0 (fail-open).
 */
@Command(
        name = "kb",
        mixinStandardHelpOptions = true,
        versionProvider = Kb.Version.class,
        description = "Client of the local agentkb server.",
        subcommands = {
            SecretCommand.class,
            RunCommand.class,
            HookCommand.class,
            MaintenanceCommands.Revert.class,
            MaintenanceCommands.Restore.class,
            MaintenanceCommands.Sync.class,
            MaintenanceCommands.Check.class,
            ImportMemoriesCommand.class,
            ReadCommands.Search.class,
            ReadCommands.Stale.class,
            ReadCommands.Show.class,
            ReadCommands.Cypher.class,
            EntityCommands.Add.class,
            EntityCommands.Edit.class,
            EntityCommands.Archive.class,
            EntityCommands.Unarchive.class,
            FactCommands.Fact.class,
            FactCommands.Rel.class,
            ReviewCommands.Conflicts.class,
            ReviewCommands.Resolve.class,
            ReviewCommands.Proposals.class,
            ReviewCommands.Accept.class,
            ReviewCommands.Reject.class,
            ReviewCommands.Activity.class,
            ScanCommand.class,
            DocCommands.Doc.class,
            DoctorCommand.class,
            KbTuiCommand.class
        })
public final class Kb {

    /**
     * kb --version: the Maven project.version, which the build writes into kb-version.properties
     * (kb-client pom.xml); native kb carries the file through resource-config.json.
     */
    static final class Version implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() throws IOException {
            Properties version = new Properties();
            try (InputStream in = Kb.class.getResourceAsStream("kb-version.properties")) {
                if (in == null) {
                    throw new IOException("kb-version.properties is missing from kb");
                }
                version.load(in);
            }
            return new String[] {"kb " + version.getProperty("version")};
        }
    }

    /** kb itself failed; for kb run, the command did not start; for kb check, it found problems. */
    static final int FAILED = 1;

    /** docs/CLIENTS.md: the server could not be reached (revert, restore, sync, check). */
    static final int UNREACHABLE = 3;

    private Kb() {}

    public static void main(String[] args) {
        PrintWriter out = utf8(System.out);
        PrintWriter err = utf8(System.err);
        CommandLine cli =
                new CommandLine(new Kb())
                        .setOut(out)
                        .setErr(err)
                        .setExecutionExceptionHandler(
                                (e, command, parsed) -> {
                                    // A message, never a stack trace (docs/SECRETS.md §3: one
                                    // clear line). Only KbException messages are known to carry
                                    // no value; a JDK message may quote one.
                                    command.getErr().println("kb: " + message(e));
                                    return e instanceof KbException kb ? kb.exitCode() : FAILED;
                                })
                        // A wrong command line is bad input, exit 1 (docs/CLIENTS.md §2 has no 2).
                        // Set last: it reaches only the subcommands already added.
                        .setExitCodeExceptionMapper(e -> FAILED);
        // kb run -- sh -c ...: everything after the command name is the command's. Only kb run:
        // elsewhere an option may follow a positional (kb fact set <id> <key> <value> --env x).
        cli.getSubcommands().get("run").setStopAtPositional(true);
        int exitCode = cli.execute(args);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    private static String message(Exception e) {
        return e instanceof KbException
                ? e.getMessage()
                : "unexpected failure (" + e.getClass().getName() + ")";
    }

    private static PrintWriter utf8(OutputStream stream) {
        return new PrintWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), true);
    }
}
