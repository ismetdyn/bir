package io.github.ismetdyn.agentkb.server.scan.extract;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What no single file knows (fixtures/scanner/expected.yaml, ornek-app-01): the project is
 * DEPLOYED_ON each Ansible host of an env, unless a k8s manifest puts it in a namespace of that
 * env, where it runs in the cluster instead. Such a host gets the server.port of the project's
 * profile of the same env as a port fact ({@code 8443/https} when server.ssl.enabled) with TARGET
 * the project, from the line of server.port; application.yml's port is default and binds only to
 * default hosts (not_expected).
 */
public final class Deployments {

    static final String DEPLOYED_ON = "DEPLOYED_ON";

    /** A server.port of {@code env}; https when server.ssl.enabled is true. */
    record Listen(String env, String port, boolean https, int line) {}

    private Deployments() {}

    // ponytail: envs compare by their normalized raw names, not through the synonyms of
    // facts.yaml#environments, which only the write knows; namespace ornek-qa and group [test] stay
    // apart. Pass the schema's mapping in here if a repository mixes synonyms.
    public static void derive(Collection<Extraction> files, String project) {
        bind(files, deploy(files, clustered(files)), project);
    }

    /** The envs a k8s manifest puts the project in. */
    private static Set<String> clustered(Collection<Extraction> files) {
        Set<String> clustered = new HashSet<>();
        for (Extraction out : files) {
            for (Found found : out.facts()) {
                if (found.entity() == null && K8s.NAMESPACE.equals(found.key())) {
                    clustered.add(Ids.normalize(found.env()));
                }
            }
        }
        return clustered;
    }

    /** DEPLOYED_ON each host of an env outside the cluster, in its file; the hosts by env. */
    private static Map<String, Set<String>> deploy(
            Collection<Extraction> files, Set<String> clustered) {
        Map<String, Set<String>> hosts = new LinkedHashMap<>();
        for (Extraction out : files) {
            for (Found found : out.facts()) {
                String env = Ids.normalize(found.env());
                if (Extraction.SERVER.equals(found.type())
                        && found.entity() != null
                        && !clustered.contains(env)
                        && hosts.computeIfAbsent(env, e -> new LinkedHashSet<>())
                                .add(found.entity())) {
                    out.link(DEPLOYED_ON, found.entity(), found.env());
                }
            }
        }
        return hosts;
    }

    /** The port fact on each deployed host, in the file of server.port. */
    private static void bind(
            Collection<Extraction> files, Map<String, Set<String>> hosts, String project) {
        for (Extraction out : files) {
            for (Listen listen : out.listens) {
                String port = listen.port() + (listen.https() ? "/https" : "/http");
                for (String host : hosts.getOrDefault(Ids.normalize(listen.env()), Set.of())) {
                    out.add(
                            new Found(
                                    host,
                                    Extraction.SERVER,
                                    "port",
                                    port,
                                    listen.env(),
                                    listen.line(),
                                    project));
                }
            }
        }
    }
}
