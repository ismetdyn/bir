package io.github.ismetdyn.agentkb.client;

import java.io.Console;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code kb secret set|show <id>} (docs/CLIENTS.md, F2: only these two; docs/SECRETS.md §4). */
@Command(
        name = "secret",
        description = "Set or show a secret value on this machine.",
        subcommands = {SecretCommand.Set.class, SecretCommand.Show.class})
final class SecretCommand {

    private SecretCommand() {}

    @Command(
            name = "set",
            description =
                    "Read the value without echo (from stdin when it is not a terminal) and store"
                            + " it.")
    static final class Set implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "<entity>/<env>/<name>")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String value = readValue();
            if (value.isEmpty()) {
                spec.commandLine().getErr().println("kb: no value given; nothing was sent");
                return Kb.FAILED;
            }
            SecretApi.fromEnvironment().put(id, value);
            spec.commandLine().getOut().println("stored; use it as kb:secret/" + id);
            return 0;
        }

        /**
         * From the console without echo; from stdin otherwise, one trailing line end dropped as
         * {@code read} does. On JDK 21 {@code System.console()} is null when stdin is not a
         * terminal; JDK 22 changes that and would need {@code Console.isTerminal()}.
         */
        private String readValue() {
            Console console = System.console();
            if (console != null) {
                char[] typed = console.readPassword("Value for %s: ", id);
                return typed == null ? "" : new String(typed);
            }
            try {
                String text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                return text.endsWith("\r\n")
                        ? text.substring(0, text.length() - 2)
                        : text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Command(name = "show", description = "Print the value stored on this machine.")
    static final class Show implements Callable<Integer> {

        @Parameters(paramLabel = "<id>", description = "<entity>/<env>/<name>")
        String id;

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            String value =
                    SecretApi.fromEnvironment()
                            .value(id)
                            .orElseThrow(() -> new KbException(RunCommand.missing(id)));
            spec.commandLine().getOut().println(value);
            return 0;
        }
    }
}
