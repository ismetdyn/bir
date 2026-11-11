package io.github.ismetdyn.agentkb.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place for agentkb's default paths (ADR-0027 decision 1, ARCHITECTURE §14). {@code init}
 * and the server's defaults both come from here, so application.yml holds no second copy.
 *
 * <ul>
 *   <li>Linux: data {@code $XDG_DATA_HOME/agentkb} ({@code ~/.local/share/agentkb}), repo {@code
 *       ~/kb}, tools {@code ~/kb-tools}. An existing {@code ~/.local/share/agentkb} stays in use
 *       (with a warning) while the XDG one does not exist yet.
 *   <li>Windows: data {@code %LOCALAPPDATA%\agentkb}, repo {@code %USERPROFILE%\kb}, tools {@code
 *       %LOCALAPPDATA%\agentkb\tools}. The JDK takes user.home from the profile directory there.
 *   <li>{@code AGENTKB_HOME} moves data, repo and tools under it; {@code AGENTKB_DATA_DIR} and
 *       {@code AGENTKB_REPO} still win, as the server's relaxed binding lets them.
 * </ul>
 *
 * The scan roots stay the user's {@code projects} and {@code git} folders on both systems.
 */
record AgentkbPaths(
        Path dataDir, Path repo, Path tools, List<Path> allowedRoots, boolean legacyDataDir) {

    private static final Logger LOG = LoggerFactory.getLogger(AgentkbPaths.class);
    private static final String NAME = "agentkb";

    AgentkbPaths {
        allowedRoots = List.copyOf(allowedRoots);
    }

    /** Resolved once per process (init or the server), so the warning is logged once. */
    static AgentkbPaths ofThisMachine() {
        AgentkbPaths paths =
                resolve(
                        System.getProperty("os.name"),
                        System.getenv(),
                        Path.of(System.getProperty("user.home")),
                        Files::isDirectory);
        if (paths.legacyDataDir()) {
            LOG.warn(
                    "event=legacy_data_dir_kept data_dir={} hint=move_it_under_XDG_DATA_HOME",
                    paths.dataDir());
        }
        return paths;
    }

    static AgentkbPaths resolve(
            String osName, Map<String, String> env, Path home, Predicate<Path> isDirectory) {
        @Nullable Path agentkbHome = set(env, "AGENTKB_HOME");
        Path base;
        Path tools;
        boolean legacy = false;
        if (agentkbHome != null) {
            base = agentkbHome;
            tools = agentkbHome.resolve("tools");
        } else if (osName.startsWith("Windows")) {
            @Nullable Path local = set(env, "LOCALAPPDATA");
            base = (local != null ? local : home.resolve("AppData").resolve("Local")).resolve(NAME);
            tools = base.resolve("tools");
        } else {
            Path legacyDir = home.resolve(".local").resolve("share").resolve(NAME);
            Path xdgDir = xdgDataDir(env, legacyDir);
            legacy = keepsLegacy(xdgDir, legacyDir, isDirectory);
            base = legacy ? legacyDir : xdgDir;
            tools = home.resolve("kb-tools");
        }
        Path repoBase = agentkbHome != null ? agentkbHome : home;
        return new AgentkbPaths(
                orElse(set(env, "AGENTKB_DATA_DIR"), base),
                orElse(set(env, "AGENTKB_REPO"), repoBase.resolve("kb")),
                tools,
                List.of(home.resolve("projects"), home.resolve("git")),
                legacy && set(env, "AGENTKB_DATA_DIR") == null);
    }

    /** XDG Base Directory spec: a relative XDG_DATA_HOME is invalid and ignored. */
    private static Path xdgDataDir(Map<String, String> env, Path fallback) {
        @Nullable Path xdg = set(env, "XDG_DATA_HOME");
        return xdg != null && xdg.isAbsolute() ? xdg.resolve(NAME) : fallback;
    }

    /**
     * An install from before ADR-0027 holds its graph in the legacy dir: keep using it rather than
     * start an empty graph beside it, until the XDG one exists.
     */
    private static boolean keepsLegacy(Path xdgDir, Path legacyDir, Predicate<Path> isDirectory) {
        return !xdgDir.equals(legacyDir)
                && !isDirectory.test(xdgDir)
                && isDirectory.test(legacyDir);
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
