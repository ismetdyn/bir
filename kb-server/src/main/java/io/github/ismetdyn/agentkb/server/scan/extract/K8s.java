package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.scan.extract.ConfigEntries.Entry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Kubernetes manifests (ARCHITECTURE §6): any YAML file with a top-level {@code kind}. Each {@code
 * ---} document is in the env of its namespace's last {@code -} part (ornek-test → test), default
 * without one. The project gets the namespace, each container image and, for an Ingress, {@code
 * https://<host>} as url. A secretKeyRef holds no value and an ingress host is no entity
 * (fixtures/scanner/expected.yaml not_expected); a k8s Secret is not read. A secret-looking plain
 * value of a container env becomes a Secret.
 */
public final class K8s {

    static final String NAMESPACE = "namespace";

    private static final Pattern FILE = Pattern.compile(".+\\.ya?ml");
    private static final Pattern MANIFEST = Pattern.compile("(?m)^kind:\\s*+\\S");
    // Relaxed keys (ConfigEntries.Entry.canonical): lower case, no - or _.
    private static final Pattern IMAGE =
            Pattern.compile("(?:.+\\.)?(?:init)?containers\\[\\d+]\\.image");
    private static final Pattern INGRESS_HOST = Pattern.compile("spec\\.rules\\[\\d+]\\.host");
    // Raw keys: <...>containers[i].env[j] and its name or value.
    private static final Pattern CONTAINER_ENV =
            Pattern.compile(
                    "((?:.+\\.)?(?:initContainers|containers)\\[\\d+]\\.env\\[\\d+])\\.(name|value)");
    private static final String KIND = "kind";
    private static final String NAMESPACE_KEY = "metadata.namespace";

    private K8s() {}

    public static boolean reads(String fileName) {
        return FILE.matcher(fileName).matches();
    }

    /**
     * @throws tools.jackson.core.JacksonException if a manifest cannot be parsed
     */
    public static void extract(String fileName, String text, Extraction out) {
        // Most YAML in a repository is no manifest (CI, OpenAPI); it is not parsed at all.
        if (!MANIFEST.matcher(text).find()) {
            return;
        }
        List<Entry> entries = ConfigEntries.yaml(text);
        Map<Integer, String> kinds = byDocument(entries, KIND);
        Map<Integer, String> namespaces = byDocument(entries, NAMESPACE_KEY);
        // A namespace or image repeated by each document is recorded once, from its first line.
        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            String kind = kinds.get(entry.document());
            String key = kind == null ? null : key(Ids.normalize(kind), entry.canonical());
            if (key == null || entry.value().isEmpty() || Extraction.isReference(entry.value())) {
                continue;
            }
            String value = "url".equals(key) ? "https://" + entry.value() : entry.value();
            String env = env(namespaces.get(entry.document()));
            if (seen.add(key + " " + env + " " + value)) {
                out.fact(null, null, key, value, env, entry.line());
            }
        }
        containerEnvSecrets(entries, kinds, namespaces, out);
    }

    /**
     * docs/SECRETS.md, lead decision E: a container env {@code name: X, value: v} whose {@code X=v}
     * looks like a secret is a Secret, as in the other extractors; a valueFrom (secretKeyRef) holds
     * no value and is not read.
     */
    private static void containerEnvSecrets(
            List<Entry> entries,
            Map<Integer, String> kinds,
            Map<Integer, String> namespaces,
            Extraction out) {
        Map<EnvItem, Entry> names = containerEnv(entries, "name");
        containerEnv(entries, "value")
                .forEach(
                        (item, value) -> {
                            Entry name = names.get(item);
                            if (name != null
                                    && kinds.containsKey(item.document())
                                    && isPlainSecret(name.value(), value.value())) {
                                out.secret(
                                        name.value(),
                                        env(namespaces.get(item.document())),
                                        value.value(),
                                        value.line());
                            }
                        });
    }

    /** One {@code containers[i].env[j]} item of one YAML document. */
    private record EnvItem(int document, String path) {}

    /** Each env item → the entry of its {@code field} (name or value). */
    private static Map<EnvItem, Entry> containerEnv(List<Entry> entries, String field) {
        // File order, so the first of two values under one secret id is the first in the file.
        Map<EnvItem, Entry> found = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Matcher env = CONTAINER_ENV.matcher(entry.key());
            if (env.matches() && field.equals(env.group(2))) {
                found.put(new EnvItem(entry.document(), env.group(1)), entry);
            }
        }
        return found;
    }

    private static boolean isPlainSecret(String name, String value) {
        return !value.isEmpty()
                && !Extraction.isReference(value)
                && SecretPatterns.looksLikeSecret(name + "=" + value);
    }

    /** The value of the relaxed key {@code canonical} in each document that has it. */
    private static Map<Integer, String> byDocument(List<Entry> entries, String canonical) {
        Map<Integer, String> values = new HashMap<>();
        for (Entry entry : entries) {
            if (canonical.equals(entry.canonical())) {
                values.put(entry.document(), entry.value());
            }
        }
        return values;
    }

    private static @Nullable String key(String kind, String canonical) {
        if (NAMESPACE_KEY.equals(canonical)) {
            return NAMESPACE;
        }
        if (IMAGE.matcher(canonical).matches()) {
            return "image";
        }
        return "ingress".equals(kind) && INGRESS_HOST.matcher(canonical).matches() ? "url" : null;
    }

    private static String env(@Nullable String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return Extraction.DEFAULT_ENV;
        }
        return namespace.substring(namespace.lastIndexOf('-') + 1);
    }
}
