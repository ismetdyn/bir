package io.github.ismetdyn.agentkb.server.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on anything but a loopback address (ADR-0020 §3): without tokens the network
 * would get the whole knowledge base.
 *
 * <p>A factory post-processor runs before any bean exists, so before the web server is created and
 * Neo4j is opened.
 */
@Component
class LoopbackAddressCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String ADDRESS_KEY = "server.address";

    // Not a constructor argument: autowiring is not available yet when factory post-processors are
    // created.
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String address = environment.getProperty(ADDRESS_KEY);
        // Checked by hand: the web server binds every interface here, yet InetAddress.getByName("")
        // returns loopback.
        if (address == null || address.isBlank()) {
            throw refusal("is not set, which binds every interface");
        }
        if (!resolvesToLoopback(address)) {
            throw refusal("'" + address + "' is not a loopback address");
        }
    }

    private static boolean resolvesToLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static IllegalStateException refusal(String problem) {
        return new IllegalStateException(
                ADDRESS_KEY
                        + " "
                        + problem
                        + "; refusing to start, use a loopback address such "
                        + "as 127.0.0.1 (ADR-0020)");
    }
}
