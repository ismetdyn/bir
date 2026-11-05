package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.FoundSecret;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Link;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the extractors found in one file (ARCHITECTURE §6: entity hint, key, value, env, line,
 * sensitive or not). Envs are raw names; the write maps their synonyms.
 */
public final class Extraction {

    static final String DEFAULT_ENV = "default";
    static final String DEPENDS_ON = "DEPENDS_ON";
    static final String SERVER = "server";
    // An address of this machine names no other system; it would only make an entity "localhost".
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1");

    // The server.port of each profile, for Deployments; never written as it is.
    final List<Deployments.Listen> listens = new ArrayList<>();

    private final List<String> internalDomains;
    private final List<Found> facts = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final List<FoundSecret> secrets = new ArrayList<>();

    public Extraction(List<String> internalDomains) {
        this.internalDomains =
                internalDomains.stream().map(domain -> Ids.normalize(domain.strip())).toList();
    }

    public List<Found> facts() {
        return List.copyOf(facts);
    }

    public List<Link> links() {
        return List.copyOf(links);
    }

    public List<FoundSecret> secrets() {
        return List.copyOf(secrets);
    }

    /** A fact of the entity {@code entity}, or of the project when it is null. */
    void fact(
            @Nullable String entity,
            @Nullable String type,
            String key,
            String value,
            String env,
            int line) {
        add(new Found(entity, type, key, value, env, line));
    }

    void add(Found found) {
        facts.add(found);
    }

    void link(String type, String target, String env) {
        links.add(new Link(type, target, env));
    }

    void secret(String name, String env, String value, int line) {
        secrets.add(new FoundSecret(name, env, value, line));
    }

    /**
     * The entity at {@code host} with its hostname, or ip, fact; null for a loopback address. The
     * id rule is fixtures/scanner/expected.yaml's: the first DNS label for a host in an internal
     * domain, else the whole host name, normalized.
     */
    @Nullable String address(String host, String type, String env, int line) {
        String name = Ids.normalize(host.strip());
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || LOOPBACK.contains(name)) {
            return null;
        }
        String id = entityOf(name);
        fact(id, type, isIp(name) ? "ip" : "hostname", name, env, line);
        return id;
    }

    /** {@link #address}'s id rule without writing a fact. */
    String entityOf(String host) {
        String name = Ids.normalize(host.strip());
        for (String domain : internalDomains) {
            if ((name.equals(domain) || name.endsWith("." + domain)) && name.contains(".")) {
                return name.substring(0, name.indexOf('.'));
            }
        }
        return name;
    }

    /**
     * {@code scheme://host[:port]/path}: invariant 2, a user part, query (sig=, key=) or fragment
     * can carry a credential that no secret pattern knows, so a url fact keeps none of them.
     */
    static String withoutUserQueryAndFragment(URI uri) {
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme()
                + "://"
                + uri.getHost()
                + port
                + Objects.requireNonNullElse(uri.getRawPath(), "");
    }

    static boolean isLoopback(String host) {
        return LOOPBACK.contains(Ids.normalize(host.strip()));
    }

    /** {@code ${DB_PASSWORD}} or {@code $DB_PASSWORD}: a reference, not a value (SECRETS.md). */
    static boolean isReference(String value) {
        return value.contains("${") || value.startsWith("$");
    }

    static boolean isIp(String host) {
        return host.contains(":") || host.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9');
    }
}
