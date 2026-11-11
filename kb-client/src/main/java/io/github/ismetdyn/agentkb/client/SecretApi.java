package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code GET} and {@code PUT /api/v1/secrets/<id>} of the local server (ADR-0022 decision 5): the
 * id with its slashes in the path, the value as a UTF-8 text body, no token and no Origin
 * (ADR-0020). The address comes from {@code AGENTKB_URL}, as for integrations/kb-hook.sh.
 */
final class SecretApi {

    static final String DEFAULT_URL = "http://127.0.0.1:7411";
    private static final String PATH = "/api/v1/secrets/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;
    private static final int NOT_FOUND = 404;

    private final String base;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    private SecretApi(String base) {
        this.base = base;
    }

    static SecretApi fromEnvironment() {
        return new SecretApi(serverUrl());
    }

    /**
     * The server address from {@code AGENTKB_URL}, without a trailing slash; every kb command that
     * talks to the server uses it (JsonApi too).
     *
     * @throws KbException if it does not name this machine
     */
    static String serverUrl() {
        String url = System.getenv("AGENTKB_URL");
        String base = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        if (!isLoopback(host(base))) {
            throw new KbException(
                    "AGENTKB_URL must name this machine (127.0.0.0/8, ::1 or localhost),"
                            + " the only place the server runs; refusing "
                            + base);
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Literal checks only, no name lookup: a resolver could map any other name anywhere. A
     * bracketed host is an IPv6 literal, which {@code getByName} parses without a lookup.
     */
    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (host.startsWith("[")) {
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Empty when the server has no value for {@code id} on this machine. */
    Optional<String> value(String id) {
        HttpResponse<String> reply = send(request(id).GET());
        return switch (reply.statusCode()) {
            case OK -> Optional.of(reply.body());
            case NOT_FOUND -> Optional.empty();
            default -> throw refused(reply);
        };
    }

    /**
     * Empty after 204. After 200 the server's note, without control characters: the value was
     * already in records as plain text, so it should be rotated (ADR-0026); it never holds the
     * value.
     */
    Optional<String> put(String id, String value) {
        HttpResponse<String> reply =
                send(
                        request(id)
                                .header("Content-Type", "text/plain; charset=UTF-8")
                                .PUT(
                                        HttpRequest.BodyPublishers.ofString(
                                                value, StandardCharsets.UTF_8)));
        return switch (reply.statusCode()) {
            case NO_CONTENT -> Optional.empty();
            case OK -> Optional.of((String) ServerText.printable(reply.body().strip()));
            default -> throw refused(reply);
        };
    }

    private HttpRequest.Builder request(String id) {
        try {
            // The multi-argument constructor quotes what a path cannot hold; slashes stay.
            URI path = new URI(null, null, PATH + id, null);
            return HttpRequest.newBuilder(URI.create(base + path.getRawPath()))
                    .timeout(REQUEST_TIMEOUT);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new KbException("not a usable secret id or AGENTKB_URL: " + id + ", " + base);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new KbException(
                    "cannot reach the agentkb server at "
                            + base
                            + " (AGENTKB_URL); is it running?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbException("interrupted while waiting for " + base);
        }
    }

    /**
     * The server's error body names what is wrong and never holds the value; its control characters
     * go, as for every server text.
     */
    private static KbException refused(HttpResponse<String> reply) {
        return new KbException(
                "the server answered "
                        + reply.statusCode()
                        + " "
                        + ServerText.printable(reply.body().strip()));
    }
}
