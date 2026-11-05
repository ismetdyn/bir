package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.scan.extract.ConfigEntries.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docker-compose*.yml} and {@code compose*.yml} (ARCHITECTURE §6), always the local env.
 * Only a plain-text secret in a service's {@code environment} (map or {@code KEY=value} list) is
 * taken, into a Secret; a compose service is no entity and adds no relation or fact
 * (fixtures/scanner/expected.yaml not_expected).
 */
public final class Compose {

    private static final Pattern FILE = Pattern.compile("(?:docker-)?compose.*\\.ya?ml");
    // services.<name>.environment.<KEY> or services.<name>.environment[i] = "KEY=value".
    private static final Pattern ENVIRONMENT =
            Pattern.compile("services\\.[^.]+\\.environment(?:\\.(.+)|\\[\\d+])");
    private static final String LOCAL_ENV = "local";

    private Compose() {}

    public static boolean reads(String fileName) {
        return FILE.matcher(fileName).matches();
    }

    /**
     * @throws tools.jackson.core.JacksonException if the YAML cannot be parsed
     */
    public static void extract(String fileName, String text, Extraction out) {
        for (Entry entry : ConfigEntries.yaml(text)) {
            Matcher environment = ENVIRONMENT.matcher(entry.key());
            if (!environment.matches()) {
                continue;
            }
            String name = environment.group(1);
            String value = entry.value();
            if (name == null) {
                int equals = value.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                name = value.substring(0, equals).strip();
                value = value.substring(equals + 1).strip();
            }
            if (!value.isEmpty()
                    && !Extraction.isReference(value)
                    && SecretPatterns.looksLikeSecret(name + "=" + value)) {
                out.secret(name, LOCAL_ENV, value, entry.line());
            }
        }
    }
}
