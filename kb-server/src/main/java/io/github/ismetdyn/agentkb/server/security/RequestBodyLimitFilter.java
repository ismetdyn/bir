package io.github.ismetdyn.agentkb.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Refuses a request body over 1 MiB before it reaches the heap of the JVM hosting Neo4j: the /mcp
 * transport reads a body whole and without a limit, and eight concurrent 16 MiB bodies under
 * -Xmx512m ended in OutOfMemoryError (ADR-0022). On /mcp/curator this filter runs before the MCP
 * SDK's own 16 MiB limit, so the lower limit applies there too.
 *
 * <p>Runs right after {@link OriginGuard}, so a refused origin or host is answered without looking
 * at the body. A body without Content-Length has no size to check up front, so at most one byte
 * over the limit is read and then replayed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestBodyLimitFilter extends HttpFilter {

    private static final int LIMIT_BYTES = 1024 * 1024;

    @Override
    protected void doFilter(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > LIMIT_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (declaredLength >= 0) {
            // Tomcat stops reading at the declared length.
            chain.doFilter(request, response);
            return;
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(LIMIT_BYTES + 1);
        } catch (IOException e) {
            // Broken chunk framing or a client that went away: a read failure here is never the
            // server's fault.
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (body.length > LIMIT_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new ReplayedBodyRequest(request, body), response);
    }

    /**
     * The /mcp transport reads the input stream and the /mcp/curator transport the reader, so both
     * replay the body.
     */
    private static final class ReplayedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ReplayedInputStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(
                    new InputStreamReader(
                            getInputStream(),
                            encoding != null ? encoding : StandardCharsets.ISO_8859_1.name()));
        }
    }

    private static final class ReplayedInputStream extends ServletInputStream {

        private final ByteArrayInputStream bytes;

        ReplayedInputStream(byte[] body) {
            this.bytes = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return bytes.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("the replayed body is read synchronously");
        }
    }
}
