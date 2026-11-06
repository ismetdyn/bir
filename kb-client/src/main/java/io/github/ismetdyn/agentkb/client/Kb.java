package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.client.tui.KbTuiCommand;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code kb} command (docs/CLIENTS.md, F2 subset): a thin client of the local server's REST
 * API; it never opens the graph (ADR-0022 decision 3). Output is UTF-8 whatever the locale says, so
 * a value can be piped exactly.
 */
@Command(
        name = "kb",
        mixinStandardHelpOptions = true,
        description = "Client of the local agentkb server.",
        subcommands = {
            SecretCommand.class,
            RunCommand.class,
            MaintenanceCommands.Revert.class,
            MaintenanceCommands.Restore.class,
            MaintenanceCommands.Sync.class,
            MaintenanceCommands.Check.class,
            ImportMemoriesCommand.class,
            KbTuiCommand.class
        })
public final class Kb {

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
                        // kb run -- sh -c ...: everything after the command name is the command's.
                        .setStopAtPositional(true)
                        .setOut(out)
                        .setErr(err)
                        .setExecutionExceptionHandler(
                                (e, command, parsed) -> {
                                    // A message, never a stack trace (docs/SECRETS.md §3: one
                                    // clear line). Only KbException messages are known to carry
                                    // no value; a JDK message may quote one.
                                    command.getErr().println("kb: " + message(e));
                                    return e instanceof KbException kb ? kb.exitCode() : FAILED;
                                });
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
