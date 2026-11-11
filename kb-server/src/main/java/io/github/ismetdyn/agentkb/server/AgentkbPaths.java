package io.github.ismetdyn.agentkb.server;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The one place for agentkb's default paths (ADR-0027 decision 1, ARCHITECTURE §14). {@code init}
 * and the server's defaults both come from here, so application.yml holds no second copy.
 *
 * <ul>
 *   <li>Linux: data {@code $XDG_DATA_HOME/agentkb} ({@code ~/.local/share/agentkb}), repo {@code
 *       ~/kb}, tools {@code ~/kb-tools}.
 *   <li>Windows: data {@code %LOCALAPPDATA%\agentkb}, repo {@code %USERPROFILE%\kb}, tools {@code
 *       %LOCALAPPDATA%\agentkb\tools}. The JDK takes user.home from the profile directory there.
 *   <li>{@code AGENTKB_HOME} moves data, repo and tools under it; {@code AGENTKB_DATA_DIR} and
 *       {@code AGENTKB_REPO} still win, as the server's relaxed binding lets them.
 * </ul>
 *
 * The scan roots stay the user's {@code projects} and {@code git} folders on both systems.
 */
record AgentkbPaths(Path dataDir, Path repo, Path tools, List<Path> allowedRoots) {

    private static final String NAME = "agentkb";

    AgentkbPaths {
        allowedRoots = List.copyOf(allowedRoots);
    }

    static AgentkbPaths ofThisMachine() {
        return resolve(
                System.getProperty("os.name"),
                System.getenv(),
                Path.of(System.getProperty("user.home")));
    }

    static AgentkbPaths resolve(String osName, Map<String, String> env, Path home) {
        @Nullable Path agentkbHome = set(env, "AGENTKB_HOME");
        Path base;
        Path tools;
        if (agentkbHome != null) {
            base = agentkbHome;
            tools = agentkbHome.resolve("tools");
        } else if (osName.startsWith("Windows")) {
            @Nullable Path local = set(env, "LOCALAPPDATA");
            base = (local != null ? local : home.resolve("AppData").resolve("Local")).resolve(NAME);
            tools = base.resolve("tools");
        } else {
            // XDG Base Directory spec: a relative value is invalid and ignored.
            @Nullable Path xdg = set(env, "XDG_DATA_HOME");
            Path dataHome =
                    xdg != null && xdg.isAbsolute() ? xdg : home.resolve(".local").resolve("share");
            base = dataHome.resolve(NAME);
            tools = home.resolve("kb-tools");
        }
        Path repoBase = agentkbHome != null ? agentkbHome : home;
        return new AgentkbPaths(
                orElse(set(env, "AGENTKB_DATA_DIR"), base),
                orElse(set(env, "AGENTKB_REPO"), repoBase.resolve("kb")),
                tools,
                List.of(home.resolve("projects"), home.resolve("git")));
    }

    /** The server's lowest-priority properties: yml, environment and arguments override them. */
    Map<String, Object> serverDefaults() {
        return Map.of(
                "agentkb.data-dir", dataDir.toString(),
                "agentkb.repo", repo.toString(),
                "agentkb.scan.allowed-roots",
                        allowedRoots.stream().map(Path::toString).collect(Collectors.joining(",")));
    }

    private static @Nullable Path set(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Path orElse(@Nullable Path value, Path fallback) {
        return value != null ? value : fallback;
    }
}
