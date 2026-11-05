package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.scan.extract.ConfigEntries.Entry;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Spring Boot {@code application*} and {@code bootstrap*} files, YAML or properties (ARCHITECTURE
 * §6): the profile is the env, from the file name or, for a {@code ---} document, its {@code
 * spring.config.activate.on-profile}; a JDBC url gives a database (engine, host, port, db name);
 * redis, rabbitmq and kafka hosts give a service; an OAuth2 {@code issuer-uri} the service the
 * project authenticates with; any other {@code *.url}, {@code *.uri} or {@code *.host} a service it
 * depends on. A value whose {@code key=value} looks like a secret becomes a Secret and nothing
 * else; a {@code ${...}} reference is skipped. {@code server.port} (with {@code
 * server.ssl.enabled}) is kept for {@link Deployments}, which knows the server it runs on.
 */
public final class SpringConfig {

    private static final Pattern FILE =
            Pattern.compile("(?:application|bootstrap)(?:-(.+))?\\.(?:yml|yaml|properties)");
    private static final Pattern ORACLE =
            Pattern.compile(
                    "jdbc:oracle:thin:@(?://)?([A-Za-z0-9.-]+)(?::(\\d+))?(?:[/:]([\\w.$#-]+))?");
    private static final Pattern URL_STYLE =
            Pattern.compile(
                    "jdbc:(postgresql|mysql|mariadb|sqlserver)://(?:[^@/;]*@)?([A-Za-z0-9.-]+)"
                            + "(?::(\\d+))?(?:/([\\w.$-]+))?");
    private static final Pattern SQLSERVER_DB = Pattern.compile(";databaseName=([^;]+)");
    private static final Pattern LIST_INDEX = Pattern.compile("\\[\\d+]$");
    // The profile a --- document applies to, relaxed keys; spring.profiles is the pre-2.4 form.
    private static final Set<String> PROFILE_KEYS =
            Set.of("spring.config.activate.onprofile", "spring.profiles");
    // One plain profile name; an expression (prod & cloud, !prod) names no single env.
    private static final Pattern PROFILE = Pattern.compile("[\\w-]+");
    // JDBC subprotocol → engine of seed/schema/facts.yaml.
    private static final Map<String, String> ENGINES =
            Map.of(
                    "postgresql", "postgresql",
                    "mysql", "mysql",
                    "mariadb", "mysql",
                    "sqlserver", "mssql");
    private static final String SERVICE = "service";
    private static final String DATABASE = "database";
    private static final String HOST = "host";
    private static final String SERVER_PORT = "server.port";
    private static final String SSL = "server.ssl.enabled";

    private SpringConfig() {}

    public static boolean reads(String fileName) {
        return FILE.matcher(fileName).matches();
    }

    /**
     * @throws tools.jackson.core.JacksonException if a YAML file cannot be parsed
     * @throws IllegalArgumentException if a properties file holds a malformed escape
     */
    public static void extract(String fileName, String text, Extraction out) {
        Matcher file = FILE.matcher(fileName);
        if (!file.matches()) {
            return;
        }
        String fileEnv = file.group(1) == null ? Extraction.DEFAULT_ENV : file.group(1);
        List<Entry> entries =
                fileName.endsWith(".properties")
                        ? ConfigEntries.properties(text)
                        : ConfigEntries.yaml(text);
        Map<String, Entry> byKey = new HashMap<>();
        Map<Integer, String> profiles = new HashMap<>();
        for (Entry entry : entries) {
            byKey.putIfAbsent(entry.canonical(), entry);
            // The same key in its own --- document, found first by server().
            byKey.putIfAbsent(entry.document() + " " + entry.canonical(), entry);
            if (PROFILE_KEYS.contains(entry.canonical())
                    && PROFILE.matcher(entry.value()).matches()) {
                profiles.putIfAbsent(entry.document(), entry.value());
            }
        }
        entries.forEach(
                entry -> read(entry, byKey, profiles.getOrDefault(entry.document(), fileEnv), out));
    }

    /** A JDBC url as SPEC §6 keeps it: no user, no password, no url fact. */
    private record Jdbc(
            String engine, String host, @Nullable String port, @Nullable String database) {}

    private static void read(Entry entry, Map<String, Entry> byKey, String env, Extraction out) {
        String value = entry.value();
        if (value.isEmpty() || Extraction.isReference(value)) {
            return;
        }
        if (SecretPatterns.looksLikeSecret(entry.key() + "=" + value)) {
            out.secret(entry.key(), env, value, entry.line());
        } else if (SERVER_PORT.equals(entry.canonical())) {
            Entry ssl = byKey.getOrDefault(entry.document() + " " + SSL, byKey.get(SSL));
            boolean https = ssl != null && Boolean.parseBoolean(ssl.value());
            out.listens.add(new Deployments.Listen(env, value, https, entry.line()));
        } else {
            infrastructure(entry, byKey, env, out);
        }
    }

    private static void infrastructure(
            Entry entry, Map<String, Entry> byKey, String env, Extraction out) {
        String key = LIST_INDEX.matcher(entry.canonical()).replaceAll("");
        if (entry.value().startsWith("jdbc:")) {
            datasource(entry, env, out);
        } else if (key.endsWith("redis.host")) {
            server("redis", entry, byKey, env, out);
        } else if (key.endsWith("rabbitmq.host")) {
            server("rabbitmq", entry, byKey, env, out);
        } else if (key.endsWith("kafka.bootstrapservers")) {
            kafka(entry, env, out);
        } else if (key.endsWith("issueruri")) {
            service(entry, "AUTHENTICATES_WITH", env, out);
        } else if (key.endsWith(".url") || key.endsWith(".uri")) {
            service(entry, Extraction.DEPENDS_ON, env, out);
        } else if (key.endsWith("." + HOST)) {
            link(out.address(entry.value(), SERVICE, env, entry.line()), env, out);
        }
    }

    private static void datasource(Entry entry, String env, Extraction out) {
        Jdbc jdbc = jdbc(entry.value());
        String id = jdbc == null ? null : out.address(jdbc.host(), DATABASE, env, entry.line());
        if (id == null) {
            return;
        }
        out.fact(id, DATABASE, "engine", jdbc.engine(), env, entry.line());
        if (jdbc.port() != null) {
            out.fact(id, DATABASE, "port", jdbc.port(), env, entry.line());
        }
        if (jdbc.database() != null) {
            out.fact(id, DATABASE, "db_name", jdbc.database(), env, entry.line());
        }
        link(id, env, out);
    }

    /** Oracle thin, PostgreSQL, MySQL/MariaDB and SQL Server urls; null for any other. */
    private static @Nullable Jdbc jdbc(String url) {
        Matcher oracle = ORACLE.matcher(url);
        if (oracle.lookingAt()) {
            return new Jdbc("oracle", oracle.group(1), oracle.group(2), oracle.group(3));
        }
        Matcher other = URL_STYLE.matcher(url);
        if (!other.lookingAt()) {
            return null;
        }
        Matcher sqlServer = SQLSERVER_DB.matcher(url);
        String database =
                other.group(4) == null && sqlServer.find() ? sqlServer.group(1) : other.group(4);
        return new Jdbc(ENGINES.get(other.group(1)), other.group(2), other.group(3), database);
    }

    /** {@code <prefix>.host} with the {@code <prefix>.port} next to it (relaxed keys). */
    private static void server(
            String engine, Entry host, Map<String, Entry> byKey, String env, Extraction out) {
        String id = out.address(host.value(), SERVICE, env, host.line());
        if (id == null) {
            return;
        }
        String prefix = host.canonical().substring(0, host.canonical().length() - HOST.length());
        Entry port =
                byKey.getOrDefault(
                        host.document() + " " + prefix + "port", byKey.get(prefix + "port"));
        out.fact(id, SERVICE, "engine", engine, env, host.line());
        if (port != null && !port.value().isEmpty() && !Extraction.isReference(port.value())) {
            out.fact(id, SERVICE, "port", port.value(), env, port.line());
        }
        link(id, env, out);
    }

    /** {@code host1:9092,host2:9092}. */
    private static void kafka(Entry servers, String env, Extraction out) {
        for (String server : servers.value().split(",")) {
            String address = server.strip();
            int colon = address.lastIndexOf(':');
            String host = colon < 0 ? address : address.substring(0, colon);
            String id = out.address(host, SERVICE, env, servers.line());
            if (id != null) {
                out.fact(id, SERVICE, "engine", "kafka", env, servers.line());
                if (colon >= 0) {
                    out.fact(
                            id, SERVICE, "port", address.substring(colon + 1), env, servers.line());
                }
                link(id, env, out);
            }
        }
    }

    /** An http(s) URL: a service with its url fact, linked to the project by {@code relation}. */
    private static void service(Entry entry, String relation, String env, Extraction out) {
        URI uri;
        try {
            uri = URI.create(entry.value());
        } catch (IllegalArgumentException e) {
            // Not a URL at all (a path, a pattern): nothing to point to.
            return;
        }
        String scheme = uri.getScheme() == null ? "" : Ids.normalize(uri.getScheme());
        String host = uri.getHost();
        if (!scheme.startsWith("http") || host == null || Extraction.isLoopback(host)) {
            return;
        }
        String id = out.entityOf(host);
        out.fact(
                id, SERVICE, "url", Extraction.withoutUserQueryAndFragment(uri), env, entry.line());
        out.link(relation, id, env);
    }

    private static void link(@Nullable String id, String env, Extraction out) {
        if (id != null) {
            out.link(Extraction.DEPENDS_ON, id, env);
        }
    }
}
