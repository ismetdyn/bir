package io.github.ismetdyn.agentkb.server.hooks;

import io.github.ismetdyn.agentkb.server.scan.RemoteUrl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The remote of a working copy, read from its .git/config without running git or touching the
 * network (SPEC S1), in the one spelling the Repo nodes are compared in: {@code host/group/name},
 * lower case, without scheme, user, password, port or {@code .git}.
 */
final class GitRemote {

    // A git config is a few hundred bytes; this only stops a huge file from being read whole.
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final String ORIGIN = "[remote \"origin\"]";
    private static final Pattern URL_LINE = Pattern.compile("^url\\s*=\\s*(.+)$");

    private GitRemote() {}

    /**
     * The origin remote, or else the first remote, of the working copy holding {@code cwd}; empty
     * outside a working copy. A worktree or submodule (.git is a file) is not followed.
     *
     * @throws IOException if .git/config exists but cannot be read
     */
    static Optional<String> of(Path cwd) throws IOException {
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path git = dir.resolve(".git");
            if (Files.isDirectory(git)) {
                return url(read(git.resolve("config"))).map(GitRemote::normalize);
            }
        }
        return Optional.empty();
    }

    /** Both spellings of a remote, git@host:group/name.git and https://host/group/name, match. */
    static String normalize(String url) {
        return RemoteUrl.normalize(url);
    }

    private static @Nullable String read(Path config) throws IOException {
        try (InputStream in = Files.newInputStream(config)) {
            return new String(in.readNBytes(MAX_CONFIG_BYTES), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static Optional<String> url(@Nullable String config) {
        if (config == null) {
            return Optional.empty();
        }
        @Nullable String first = null;
        String section = "";
        for (String raw : config.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                section = line;
                continue;
            }
            Matcher url = URL_LINE.matcher(line);
            if (section.startsWith("[remote ") && url.matches()) {
                if (ORIGIN.equals(section)) {
                    return Optional.of(url.group(1));
                }
                if (first == null) {
                    first = url.group(1);
                }
            }
        }
        return Optional.ofNullable(first);
    }
}
