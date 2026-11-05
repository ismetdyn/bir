package io.github.ismetdyn.agentkb.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.stereotype.Component;

/**
 * Leaves MCP error responses with their status and no body.
 *
 * <p>Both transports write their McpError exception as the body, with stack trace, cause and
 * sometimes the rejected payload, and neither offers a setting for it. The /mcp and /mcp/curator
 * transports also serialize it differently, so the one place they share is the servlet response.
 * MCP clients act on the HTTP status; the body is optional.
 */
@Component
class McpErrorBodyFilter extends FilterRegistrationBean<HttpFilter> {

    private static final int FIRST_ERROR_STATUS = HttpServletResponse.SC_BAD_REQUEST;
    // Matches /mcp and everything below it, including /mcp/curator.
    private static final String MCP_PATHS = "/mcp/*";

    McpErrorBodyFilter() {
        super(
                new HttpFilter() {
                    @Override
                    protected void doFilter(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws IOException, ServletException {
                        chain.doFilter(request, new ErrorBodyDiscardingResponse(response));
                    }
                });
        addUrlPatterns(MCP_PATHS);
    }

    private static final class ErrorBodyDiscardingResponse extends HttpServletResponseWrapper {

        ErrorBodyDiscardingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return getStatus() >= FIRST_ERROR_STATUS
                    ? new DiscardingOutputStream()
                    : super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            return getStatus() >= FIRST_ERROR_STATUS
                    ? new PrintWriter(OutputStream.nullOutputStream())
                    : super.getWriter();
        }
    }

    private static final class DiscardingOutputStream extends ServletOutputStream {

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            throw new UnsupportedOperationException("error bodies are discarded synchronously");
        }

        @Override
        public void write(int value) {
            // Discarded.
        }
    }
}
