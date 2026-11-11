package io.github.ismetdyn.agentkb.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0027 decision 1: the default paths per operating system, in one place. AGENTKB_HOME moves
 * them all; AGENTKB_DATA_DIR and AGENTKB_REPO still win over it, as the server's relaxed binding
 * lets them. The OS, the environment and the home are injected, so the Windows table runs on Linux
 * too (the separators are the host's; what is checked is which base each path grows from).
 */
class AgentkbPathsTest {

    private static final String LINUX = "Linux";
    private static final String WINDOWS = "Windows 11";
    private static final Path HOME = Path.of("/home/ayse");

    @Test
    void linux_defaults_follow_xdg_with_the_known_fallbacks() {
        AgentkbPaths paths = AgentkbPaths.resolve(LINUX, Map.of(), HOME);

        assertThat(paths.dataDir()).isEqualTo(HOME.resolve(".local/share/agentkb"));
        assertThat(paths.repo()).isEqualTo(HOME.resolve("kb"));
        assertThat(paths.tools()).isEqualTo(HOME.resolve("kb-tools"));
        assertThat(paths.allowedRoots())
                .containsExactly(HOME.resolve("projects"), HOME.resolve("git"));
    }

    @Test
    void linux_data_moves_with_an_absolute_xdg_data_home_and_ignores_a_relative_one() {
        assertThat(
                        AgentkbPaths.resolve(LINUX, Map.of("XDG_DATA_HOME", "/data/ayse"), HOME)
                                .dataDir())
                .isEqualTo(Path.of("/data/ayse/agentkb"));
        // XDG Base Directory spec: a relative value is invalid and ignored.
        assertThat(AgentkbPaths.resolve(LINUX, Map.of("XDG_DATA_HOME", "rel"), HOME).dataDir())
                .isEqualTo(HOME.resolve(".local/share/agentkb"));
    }

    @Test
    void windows_defaults_live_under_local_app_data_and_the_profile() {
        Path local = Path.of("/Users/ayse/AppData/Local");

        AgentkbPaths paths =
                AgentkbPaths.resolve(
                        WINDOWS,
                        Map.of("LOCALAPPDATA", local.toString(), "XDG_DATA_HOME", "/ignored"),
                        HOME);

        assertThat(paths.dataDir()).isEqualTo(local.resolve("agentkb"));
        assertThat(paths.tools()).isEqualTo(local.resolve("agentkb").resolve("tools"));
        assertThat(paths.repo()).isEqualTo(HOME.resolve("kb"));
        assertThat(paths.allowedRoots())
                .containsExactly(HOME.resolve("projects"), HOME.resolve("git"));
    }

    @Test
    void windows_without_local_app_data_falls_back_to_the_profile_default() {
        assertThat(AgentkbPaths.resolve(WINDOWS, Map.of(), HOME).dataDir())
                .isEqualTo(HOME.resolve("AppData").resolve("Local").resolve("agentkb"));
    }

    @Test
    void agentkb_home_moves_every_path_on_both_systems() {
        Path base = Path.of("/opt/kbhome");
        for (String os : List.of(LINUX, WINDOWS)) {
            AgentkbPaths paths =
                    AgentkbPaths.resolve(
                            os,
                            Map.of(
                                    "AGENTKB_HOME",
                                    base.toString(),
                                    "LOCALAPPDATA",
                                    "/elsewhere",
                                    "XDG_DATA_HOME",
                                    "/elsewhere"),
                            HOME);

            assertThat(paths.dataDir()).as(os).isEqualTo(base);
            assertThat(paths.repo()).as(os).isEqualTo(base.resolve("kb"));
            assertThat(paths.tools()).as(os).isEqualTo(base.resolve("tools"));
            assertThat(paths.allowedRoots())
                    .as("%s: the projects stay the user's", os)
                    .containsExactly(HOME.resolve("projects"), HOME.resolve("git"));
        }
    }

    @Test
    void the_specific_variables_win_over_agentkb_home_and_blank_ones_count_as_unset() {
        AgentkbPaths paths =
                AgentkbPaths.resolve(
                        LINUX,
                        Map.of(
                                "AGENTKB_HOME", "/opt/kbhome",
                                "AGENTKB_DATA_DIR", "/srv/data",
                                "AGENTKB_REPO", "/srv/kb"),
                        HOME);
        AgentkbPaths blank =
                AgentkbPaths.resolve(
                        LINUX,
                        Map.of("AGENTKB_HOME", " ", "AGENTKB_DATA_DIR", "", "AGENTKB_REPO", ""),
                        HOME);

        assertThat(paths.dataDir()).isEqualTo(Path.of("/srv/data"));
        assertThat(paths.repo()).isEqualTo(Path.of("/srv/kb"));
        assertThat(blank).isEqualTo(AgentkbPaths.resolve(LINUX, Map.of(), HOME));
    }

    /** The server takes its defaults from here, so application.yml holds no second copy. */
    @Test
    void the_server_defaults_are_the_resolved_paths() {
        AgentkbPaths paths = AgentkbPaths.resolve(LINUX, Map.of(), HOME);

        assertThat(paths.serverDefaults())
                .containsOnly(
                        Map.entry(
                                "agentkb.data-dir",
                                HOME.resolve(".local/share/agentkb").toString()),
                        Map.entry("agentkb.repo", HOME.resolve("kb").toString()),
                        Map.entry(
                                "agentkb.scan.allowed-roots",
                                HOME.resolve("projects") + "," + HOME.resolve("git")));
    }
}
