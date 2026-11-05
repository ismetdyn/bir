package io.github.ismetdyn.agentkb.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Stops browsers and DNS rebinding (ADR-0020 §2): real clients send no Origin and address the
 * server by its loopback name.
 *
 * <p>A servlet filter on every path, because /mcp/curator sits outside DispatcherServlet. The
 * comparison is exact and case-sensitive on purpose: a value real clients never send is not worth a
 * normalization rule.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class OriginGuard extends HttpFilter {

    @Override
    protected void doFilter(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getHeader(HttpHeaders.ORIGIN) != null || !isLoopbackHost(request)) {
            // setStatus, not sendError: an error dispatch would run more code for a request that is
            // already refused.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoopbackHost(HttpServletRequest request) {
        String host = request.getHeader(HttpHeaders.HOST);
        int port = request.getLocalPort();
        return ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host);
    }
}
