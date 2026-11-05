package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code validator} and {@code enum} rules of schema/facts.yaml (ADR-0022 decision 31). Only
 * new MCP and REST writes are checked; import keeps every value (invariant 11). Nothing here does a
 * DNS lookup or opens a connection (invariant 3): every check is on the text alone.
 */
final class FactValues {

    // RFC 1123: labels of 1-63 letters, digits and inner hyphens; 253 characters in all.
    private static final Pattern HOSTNAME =
            Pattern.compile(
                    "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern IPV4 =
            Pattern.compile(
                    "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                            + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    // Only these characters reach the IPv6 check, so URI parses a bare literal and nothing else.
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    // 8443/https: a port number and an optional protocol.
    private static final Pattern PORT_SPEC =
            Pattern.compile("(\\d{1,5})(?:/[A-Za-z][A-Za-z0-9-]*)?");
    private static final int MAX_PORT = 65_535;
    // A password a secret check alone can miss: password= in any case, user:password@ in the
    // authority and Oracle thin user/password@.
    private static final Pattern JDBC_CREDENTIALS =
            Pattern.compile(
                    "password=|://[^/@]*:[^/@]*@|^jdbc:oracle:[A-Za-z]+:[^@/]+/[^@]*@",
                    Pattern.CASE_INSENSITIVE);

    private static final String HINT =
            "Write the value in the form kb_schema gives for this key: hostname as a DNS name,"
                    + " ip as an IPv4 or IPv6 address, port as 8443 or 8443/https, url with its"
                    + " scheme (https://...), jdbc_url as jdbc:... without user or password, an enum"
                    + " key as one of the values kb_schema lists.";

    private FactValues() {}

    /**
     * @throws WriteRejectedException naming the position, never the value, if a rule of the key
     *     refuses it
     * @throws IllegalStateException if facts.yaml names a validator this server does not know
     */
    static void check(FactKey key, String value, String where) {
        String validator = key.rules().get("validator");
        if (validator != null && !accepts(validator, value)) {
            throw new WriteRejectedException(where + ": value is not a valid " + validator, HINT);
        }
        String allowed = key.rules().get("enum");
        if (allowed != null && !Arrays.asList(allowed.split(",\\s*")).contains(value)) {
            // The allowed values stay out of the message: "postgres" is part of "postgresql".
            throw new WriteRejectedException(where + ": value is not an allowed value", HINT);
        }
    }

    private static boolean accepts(String validator, String value) {
        return switch (validator) {
            case "hostname" -> HOSTNAME.matcher(value).matches();
            case "ip" -> IPV4.matcher(value).matches() || isIpv6(value);
            case "port-spec" -> isPortSpec(value);
            case "url" -> isUrl(value);
            case "jdbc-url-without-password" ->
                    value.startsWith("jdbc:")
                            && value.chars().noneMatch(Character::isWhitespace)
                            && !JDBC_CREDENTIALS.matcher(value).find();
            default ->
                    throw new IllegalStateException(
                            "facts.yaml names an unknown validator: " + validator);
        };
    }

    // URI validates an IPv6 literal (groups, ::, an IPv4 tail) without any lookup.
    private static boolean isIpv6(String value) {
        return IPV6_CHARS.matcher(value).matches() && parses("http://[" + value + "]/");
    }

    private static boolean isPortSpec(String value) {
        Matcher port = PORT_SPEC.matcher(value);
        if (!port.matches()) {
            return false;
        }
        int number = Integer.parseInt(port.group(1));
        return number >= 1 && number <= MAX_PORT;
    }

    private static boolean isUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean parses(String uri) {
        try {
            new URI(uri);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
