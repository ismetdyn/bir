package io.github.ismetdyn.agentkb.server.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Gives a hostname a fixed non-loopback address, with no DNS or /etc/hosts edit, so "a hostname
 * resolving elsewhere" is reproducible on any machine. Every other name goes to the JDK resolver.
 * Registered in META-INF/services for tests only.
 */
public class HostsForTest extends InetAddressResolverProvider {

    static final String RESOLVES_ELSEWHERE = "resolves-elsewhere.agentkb.test";
    // TEST-NET-1 (RFC 5737): never assigned, so it cannot be a local interface.
    private static final byte[] NON_LOOPBACK_ADDRESS = {(byte) 192, 0, 2, 1};

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                return RESOLVES_ELSEWHERE.equals(host)
                        ? Stream.of(InetAddress.getByAddress(host, NON_LOOPBACK_ADDRESS))
                        : builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "agentkb-test-hosts";
    }
}
