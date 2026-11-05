package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code .env} and {@code .env.<env>} (ARCHITECTURE §6): {@code .env} is the local env, a suffix
 * names its env. Only secret-looking assignments are taken, into Secret nodes; other keys of a
 * dotenv file are left to the agent. Template files ({@code .env.example}) hold no real values.
 */
public final class DotEnv {

    private static final Pattern FILE = Pattern.compile("\\.env(?:\\.(.+))?");
    private static final Set<String> TEMPLATES = Set.of("example", "sample", "template", "dist");
    private static final Pattern ASSIGNMENT =
            Pattern.compile("\\s*+(?:export\\s++)?([A-Za-z_][A-Za-z0-9_.]*+)\\s*+=(.*)");
    // An unquoted value ends where a comment starts: whitespace, then #.
    private static final Pattern COMMENT = Pattern.compile("\\s#");
    private static final String LOCAL_ENV = "local";

    private DotEnv() {}

    public static boolean reads(String fileName) {
        Matcher file = FILE.matcher(fileName);
        return file.matches()
                && (file.group(1) == null || !TEMPLATES.contains(Ids.normalize(file.group(1))));
    }

    public static void extract(String fileName, String text, Extraction out) {
        Matcher file = FILE.matcher(fileName);
        if (!file.matches()) {
            return;
        }
        String env = file.group(1) == null ? LOCAL_ENV : file.group(1);
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher assignment = ASSIGNMENT.matcher(lines.get(i));
            if (!assignment.matches()) {
                continue;
            }
            String key = assignment.group(1);
            String value = value(assignment.group(2));
            if (!value.isEmpty()
                    && !Extraction.isReference(value)
                    && SecretPatterns.looksLikeSecret(key + "=" + value)) {
                out.secret(key, env, value, i + 1);
            }
        }
    }

    /** A quoted value up to its closing quote, else up to a comment. */
    private static String value(String raw) {
        String value = raw.strip();
        if (!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
            int end = value.indexOf(value.charAt(0), 1);
            return end < 0 ? value.substring(1) : value.substring(1, end);
        }
        Matcher comment = COMMENT.matcher(value);
        return (comment.find() ? value.substring(0, comment.start()) : value).strip();
    }
}
