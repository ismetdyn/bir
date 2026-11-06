package io.github.ismetdyn.agentkb.server.scan;

import io.github.ismetdyn.agentkb.core.Ids;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one spelling Repo nodes keep and compare a remote in: {@code host/group/name}, lower case,
 * without scheme, user, password, port or {@code .git}. The scanner writes it and the hooks match a
 * working copy against it, so both use this.
 */
public final class RemoteUrl {

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
    // user@host:path, the scp-like form ssh remotes use.
    private static final Pattern SCP_LIKE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(.+)$");

    private RemoteUrl() {}

    /**
     * The Repo id: {@code <group>/<name>} of the normalized remote, else {@code local/<folder>}.
     */
    static String repoId(Optional<String> remote, String folder) {
        return remote.map(url -> url.substring(url.indexOf('/') + 1)).orElse("local/" + folder);
    }

    /** The name the folder {@code dir} gives a project or a Repo without a remote, normalized. */
    static String folder(Path dir) {
        return Ids.normalize(dir.getFileName() == null ? "root" : dir.getFileName().toString());
    }

    /** Both spellings of a remote, git@host:group/name.git and https://host/group/name, match. */
    public static String normalize(String url) {
        String rest = url.strip();
        Matcher scheme = SCHEME.matcher(rest);
        if (scheme.find()) {
            rest = rest.substring(scheme.end());
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String host = authority.substring(authority.lastIndexOf('@') + 1);
            int port = host.lastIndexOf(':');
            rest =
                    (port < 0 ? host : host.substring(0, port))
                            + (slash < 0 ? "" : rest.substring(slash));
        } else {
            Matcher scp = SCP_LIKE.matcher(rest);
            if (scp.matches()) {
                rest = scp.group(1) + "/" + scp.group(2);
            }
        }
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.endsWith(".git")) {
            rest = rest.substring(0, rest.length() - ".git".length());
        }
        return Ids.normalize(rest);
    }
}
