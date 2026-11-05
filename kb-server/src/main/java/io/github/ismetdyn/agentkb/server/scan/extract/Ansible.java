package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Ansible INI inventories, {@code inventory*}, {@code hosts*} and {@code *.ini} (ARCHITECTURE §6):
 * each host of a {@code [group]} is a server in the env of its group, with its {@code ansible_host}
 * (or its own name if that is an address) as ip or hostname. Hosts before any group are default;
 * {@code :vars} and {@code :children} sections hold no hosts. A host with no address is skipped. A
 * secret-looking variable becomes a Secret named {@code <host>.<variable>}; a {@code {{ ... }}}
 * template is a reference, not a value.
 */
public final class Ansible {

    private static final Pattern FILE = Pattern.compile("(?:inventory|hosts).*|.+\\.ini");
    private static final Pattern SECTION = Pattern.compile("\\[([^]:]+)(:[^]]*)?]");
    // host[:port] then key=value pairs, a value quoted or up to whitespace; a trailing comment.
    private static final String VALUE = "(?:\"[^\"]*\"|'[^']*'|\\S*)";
    private static final Pattern HOST_LINE =
            Pattern.compile(
                    "([A-Za-z0-9][A-Za-z0-9._-]*)(?::\\d+)?((?:\\s+[A-Za-z_]\\w*="
                            + VALUE
                            + ")*)\\s*(?:[#;].*)?");
    private static final Pattern VARIABLE = Pattern.compile("([A-Za-z_]\\w*)=(" + VALUE + ")");

    private Ansible() {}

    /** A YAML inventory is not read; a YAML file named hosts* goes to the other extractors. */
    public static boolean reads(String fileName) {
        return FILE.matcher(fileName).matches()
                && !fileName.endsWith(".yml")
                && !fileName.endsWith(".yaml");
    }

    public static void extract(String fileName, String text, Extraction out) {
        List<String> lines = text.lines().toList();
        String group = Extraction.DEFAULT_ENV;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                group = section.group(2) == null ? section.group(1).strip() : null;
            } else if (group != null && !line.startsWith("#") && !line.startsWith(";")) {
                Matcher host = HOST_LINE.matcher(line);
                if (host.matches()) {
                    host(host.group(1), variables(host.group(2)), group, i + 1, out);
                }
            }
        }
    }

    private static Map<String, String> variables(String text) {
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher variable = VARIABLE.matcher(text);
        while (variable.find()) {
            variables.put(variable.group(1), unquoted(variable.group(2)));
        }
        return variables;
    }

    private static void host(
            String name, Map<String, String> variables, String env, int line, Extraction out) {
        String id = out.entityOf(name);
        variables.forEach(
                (key, value) -> {
                    if (!value.isEmpty()
                            && !Extraction.isReference(value)
                            && !value.contains("{{")
                            && SecretPatterns.looksLikeSecret(key + "=" + value)) {
                        out.secret(id + "." + key, env, value, line);
                    }
                });
        String address = address(name, variables.get("ansible_host"));
        if (address != null) {
            out.fact(
                    id,
                    Extraction.SERVER,
                    Extraction.isIp(address) ? "ip" : "hostname",
                    address,
                    env,
                    line);
        }
    }

    /** ansible_host, else the name if it is a dotted host or ip; null for none or loopback. */
    private static @Nullable String address(String name, @Nullable String ansibleHost) {
        String address = ansibleHost == null ? name : ansibleHost;
        if ((ansibleHost == null && !name.contains("."))
                || address.isBlank()
                || address.contains("{{")
                || Extraction.isLoopback(address)) {
            return null;
        }
        return Ids.normalize(address.strip());
    }

    private static String unquoted(String value) {
        boolean quoted =
                value.length() >= 2
                        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                        && value.charAt(value.length() - 1) == value.charAt(0);
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
