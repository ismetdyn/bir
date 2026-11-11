package io.github.ismetdyn.agentkb.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Runs integrations/install.sh the way a user does on an empty machine, but sealed off from this
 * one: a cleared environment, HOME a temporary directory, PATH only stubs for java, systemctl and
 * loginctl (they log their arguments to {@link #log}) plus this machine's /usr/bin without java,
 * git, systemd tools, sudo or network clients; the real git is added back as a stub-dir link. No
 * DBUS or XDG variable reaches the script, so no real user manager and no real ~/.claude, ~/.codex,
 * ~/kb, ~/.config/systemd or ~/.local is touched. The jars come from {@code AGENTKB_SERVER_JAR} and
 * {@code AGENTKB_CLIENT_JAR} (contract fixed by the install tests). {@code AGENTKB_CLIENT_NATIVE}
 * is empty unless a test sets {@link #clientNative}, so a native kb built in this checkout never
 * leaks into the jar tests.
 */
final class InstallSandbox {

    static final Path SCRIPT =
            Path.of("..", "integrations", "install.sh").toAbsolutePath().normalize();
    static final Path INTEGRATIONS = SCRIPT.getParent();
    static final String SERVER_JAR_NAME = "agentkb-server.jar";
    static final List<String> UNITS =
            List.of("agentkb.service", "agentkb-sync.service", "agentkb-sync.timer");

    /** Existing agent settings of the user; install never changes them. */
    static final Map<String, String> AGENT_SETTINGS =
            Map.of(
                    ".claude/settings.json",
                    "{\"hooks\": {\"Stop\": [{\"hooks\": [{\"type\": \"command\","
                            + " \"command\": \"zz-own-hook.sh\"}]}]}}\n",
                    ".claude/CLAUDE.md",
                    "# zz own global rules\n",
                    ".codex/config.toml",
                    "model = \"zz-own-model\"\n",
                    ".codex/hooks.json",
                    "{\"hooks\": {}}\n");

    private static final Path REAL_GIT = Path.of("/usr/bin/git");
    private static final Path BASH = Path.of("/usr/bin/bash");
    private static final long TIMEOUT_SECONDS = 60;
    private static final Set<String> DENIED =
            Set.of(
                    "systemctl",
                    "loginctl",
                    "systemd-run",
                    "busctl",
                    "sudo",
                    "pkexec",
                    "su",
                    "doas",
                    "curl",
                    "wget",
                    "kb");
    private static final String LOGGING_STUB =
            """
            #!/bin/sh
            { echo "@@ %1$s"; for a in "$@"; do echo "$a"; done; } >> '%2$s'
            exit 0
            """;
    private static final String JAVA_STUB =
            """
            #!/bin/sh
            { echo "@@ java"; for a in "$@"; do echo "$a"; done; } >> '%1$s'
            for a in "$@"; do
              case "$a" in -jar|-cp|-classpath) exit 0 ;; esac
            done
            case "$1" in
              --version|--show-version)
                echo "openjdk %2$d.0.1 2026-01-20"
                echo "OpenJDK Runtime Environment (build %2$d.0.1+1)" ;;
              -XshowSettings*)
                echo "    java.specification.version = %2$d" >&2
                echo "    java.version = %2$d.0.1" >&2
                echo "openjdk version \\"%2$d.0.1\\" 2026-01-20" >&2 ;;
              *)
                echo "openjdk version \\"%2$d.0.1\\" 2026-01-20" >&2
                echo "OpenJDK Runtime Environment (build %2$d.0.1+1)" >&2 ;;
            esac
            exit 0
            """;
    // ponytail: built once per JVM under target/; `clean` removes it.
    private static Path systemBin;

    final Path home;
    final Path stubBin;
    final Path log;
    final Path serverJar;
    final Path clientJar;

    /** A native kb build; when run it logs as {@code kb-native}. */
    final Path nativeKb;

    /**
     * The {@code AGENTKB_CLIENT_NATIVE} the script gets: empty means no native kb, null leaves it
     * unset so the script looks for its checkout's {@code kb-client/target/kb}.
     */
    @Nullable String clientNative = "";

    /** The install.sh to run; a test may point it into a copied checkout. */
    Path script = SCRIPT;

    record Run(int exit, String stdout, String stderr) {}

    /** {@code home} stays empty; stubs, log and jars go under {@code tools}. */
    InstallSandbox(Path home, Path tools) throws IOException {
        this.home = home;
        this.stubBin = Files.createDirectories(tools.resolve("bin"));
        this.log = Files.createFile(tools.resolve("calls.log"));
        javaStub(21);
        for (String name : List.of("systemctl", "loginctl")) {
            executable(stubBin.resolve(name), LOGGING_STUB.formatted(name, log));
        }
        Files.createSymbolicLink(stubBin.resolve("git"), REAL_GIT);
        serverJar = Files.writeString(tools.resolve("server-build.jar"), "zz fake server jar");
        clientJar = Files.writeString(tools.resolve("client-build.jar"), "zz fake client jar");
        nativeKb = tools.resolve("kb-native-build");
        executable(nativeKb, LOGGING_STUB.formatted("kb-native", log));
    }

    /** A java stub that answers every version question with {@code version}. */
    void javaStub(int version) throws IOException {
        executable(stubBin.resolve("java"), JAVA_STUB.formatted(log, version));
    }

    Run install(String... args) {
        List<String> command = new ArrayList<>(List.of(BASH.toString(), script.toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    Run run(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(log.getParent().toFile());
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("HOME", home.toString());
        env.put("LANG", "C.UTF-8");
        env.put("AGENTKB_SERVER_JAR", serverJar.toString());
        env.put("AGENTKB_CLIENT_JAR", clientJar.toString());
        if (clientNative != null) {
            env.put("AGENTKB_CLIENT_NATIVE", clientNative);
        }
        try {
            env.put("PATH", stubBin + ":" + systemBin());
            Process process = builder.start();
            CompletableFuture<String> out = drain(process.getInputStream());
            CompletableFuture<String> err = drain(process.getErrorStream());
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError(command + " did not exit in " + TIMEOUT_SECONDS + " s");
            }
            return new Run(process.exitValue(), out.join(), err.join());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** Every stub call, its name first, then its arguments. */
    List<List<String>> calls() throws IOException {
        List<List<String>> calls = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.startsWith("@@ ")) {
                calls.add(new ArrayList<>(List.of(line.substring(3))));
            } else if (!calls.isEmpty()) {
                calls.getLast().add(line);
            }
        }
        return calls;
    }

    /** {@code files} (path under HOME → text) written into HOME. */
    void write(Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = home.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** Every entry under HOME: kind, permissions and content hash, by relative path. */
    Map<String, String> tree() throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(home)) {
            for (Path entry : walk.filter(p -> !p.equals(home)).toList()) {
                String relative = home.relativize(entry).toString();
                if (Files.isSymbolicLink(entry)) {
                    entries.put(relative, "link " + Files.readSymbolicLink(entry));
                    continue;
                }
                String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(entry));
                entries.put(
                        relative,
                        Files.isDirectory(entry)
                                ? "dir " + mode
                                : "file " + mode + " " + sha256(Files.readAllBytes(entry)));
            }
        }
        return entries;
    }

    private static synchronized Path systemBin() throws IOException {
        if (systemBin == null) {
            Path bin =
                    Files.createTempDirectory(
                            Files.createDirectories(Path.of("target")), "install-system-bin-");
            try (Stream<Path> entries = Files.list(Path.of("/usr/bin"))) {
                for (Path tool : entries.toList()) {
                    String name = tool.getFileName().toString();
                    boolean denied = DENIED.contains(name) || name.startsWith("git");
                    if (!denied
                            && Files.isExecutable(tool)
                            && !tool.toRealPath().startsWith("/usr/lib/jvm")) {
                        Files.createSymbolicLink(bin.resolve(name), tool);
                    }
                }
            }
            systemBin = bin.toAbsolutePath();
        }
        return systemBin;
    }

    private static void executable(Path file, String text) throws IOException {
        Files.deleteIfExists(file);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (stream) {
                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
