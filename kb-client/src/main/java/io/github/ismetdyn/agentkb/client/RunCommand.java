package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code kb run} (docs/SECRETS.md §3): resolves every {@code kb:secret/<id>} placeholder before
 * anything starts, gives the values only to the child (environment, or a 0600 file for {@code
 * --file}), masks them in the child's output and returns its exit code. A value that cannot be had
 * stops the command before it runs.
 */
@Command(
        name = "run",
        description = "Run a command with secret values in its environment, its output masked.")
final class RunCommand implements Callable<Integer> {

    static final String PLACEHOLDER = "kb:secret/";
    private static final String ENV = "--env";
    private static final char NUL = '\u0000';
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rwx------");

    @Option(
            names = "--env",
            paramLabel = "NAME=kb:secret/<id>",
            description = "Put the value into the variable NAME.")
    List<String> envs = new ArrayList<>();

    @Option(
            names = "--file",
            paramLabel = "NAME=kb:secret/<id>",
            description = "Write the value to a private temporary file; NAME holds its path.")
    List<String> files = new ArrayList<>();

    @Parameters(arity = "1..*", paramLabel = "<command>", description = "The command and its args.")
    List<String> command;

    /** The one line that tells the user how to get a missing value onto this machine. */
    static String missing(String id) {
        return "no value for " + id + " on this machine; run: kb secret set " + id;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        SecretApi api = SecretApi.fromEnvironment();
        Map<String, String> envValues = resolve(api, envs, ENV);
        Map<String, String> fileValues = resolve(api, files, "--file");
        List<Path> written = new CopyOnWriteArrayList<>();
        AtomicReference<Process> child = new AtomicReference<>();
        // SIGTERM/SIGINT run only shutdown hooks, not the finally below: the hook still removes the
        // files and stops the child, which would otherwise keep the value in its environment.
        Runtime.getRuntime()
                .addShutdownHook(
                        Thread.ofPlatform()
                                .unstarted(
                                        () -> {
                                            Process started = child.get();
                                            if (started != null) {
                                                started.destroy();
                                            }
                                            written.forEach(path -> path.toFile().delete());
                                        }));
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.environment().putAll(envValues);
            for (Map.Entry<String, String> file : fileValues.entrySet()) {
                Path path = privateFile(file.getValue());
                written.add(path);
                builder.environment().put(file.getKey(), path.toString());
            }
            List<String> values = new ArrayList<>(envValues.values());
            values.addAll(fileValues.values());
            child.set(start(builder));
            return runMasked(child.get(), OutputMasker.forms(values));
        } finally {
            for (Path path : written) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * NAME to value for each {@code NAME=kb:secret/<id>}; every value is fetched before the child
     * starts. A plain value is refused unsent and unechoed: it would sit on the command line.
     */
    private static Map<String, String> resolve(
            SecretApi api, List<String> bindings, String option) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String binding : bindings) {
            int equals = binding.indexOf('=');
            if (equals <= 0 || !binding.startsWith(PLACEHOLDER, equals + 1)) {
                String name = equals <= 0 ? "" : " " + binding.substring(0, equals);
                throw new KbException(
                        option
                                + name
                                + " takes NAME=kb:secret/<id>; a plain value would show"
                                + " on the command line");
            }
            String id = binding.substring(equals + 1 + PLACEHOLDER.length());
            String value = api.value(id).orElseThrow(() -> new KbException(missing(id)));
            // An environment value cannot hold a NUL, and the JDK's refusal quotes the value.
            if (ENV.equals(option) && value.indexOf(NUL) >= 0) {
                throw new KbException(
                        "the value of "
                                + id
                                + " holds a NUL byte, which --env cannot pass; use --file");
            }
            values.put(binding.substring(0, equals), value);
        }
        return values;
    }

    /** The command's own start failure (not found, not executable) names only the command. */
    private static Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new KbException(e.getMessage());
        }
    }

    private static Path privateFile(String value) throws IOException {
        Path path =
                Files.createTempFile(
                        tempDir(),
                        "kb-secret-",
                        "",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    /**
     * {@code $XDG_RUNTIME_DIR} when it is the user's own 0700 directory (a tmpfs: the value never
     * reaches a disk), else the JDK temp dir as before.
     */
    private static Path tempDir() {
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"));
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime == null || runtime.isBlank()) {
            return fallback;
        }
        Path dir = Path.of(runtime);
        try {
            PosixFileAttributes attributes = Files.readAttributes(dir, PosixFileAttributes.class);
            boolean isPrivate =
                    dir.isAbsolute()
                            && attributes.isDirectory()
                            && attributes.permissions().equals(OWNER_ONLY)
                            && attributes.owner().getName().equals(System.getProperty("user.name"));
            return isPrivate ? dir : fallback;
        } catch (IOException e) {
            // Missing or unreadable: the XDG spec says to fall back, so this is not an error.
            return fallback;
        }
    }

    private static int runMasked(Process child, List<byte[]> forms) throws InterruptedException {
        Thread out = pump(child.getInputStream(), System.out, forms);
        Thread err = pump(child.getErrorStream(), System.err, forms);
        int exitCode = child.waitFor();
        out.join();
        err.join();
        return exitCode;
    }

    private static Thread pump(InputStream from, OutputStream to, List<byte[]> forms) {
        return Thread.ofPlatform()
                .start(
                        () -> {
                            try (from) {
                                new OutputMasker(forms, to).copy(from);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
    }
}
