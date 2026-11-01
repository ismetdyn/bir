package io.github.ismetdyn.agentkb.server.mcp.curator;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.stereotype.Component;

/**
 * The curator MCP endpoint, hand-built with the MCP Java SDK next to the Spring AI one on /mcp.
 *
 * <p>The transport, server and tool specification stay private on purpose. As beans they would disable or leak into the
 * Spring AI auto-configuration: McpSyncServer and McpServerTransportProviderBase are {@code @ConditionalOnMissingBean},
 * and SyncToolSpecification beans are collected into the /mcp tool list.
 */
@Component
class CuratorMcpEndpoint extends ServletRegistrationBean<HttpServletStreamableServerTransportProvider>
        implements DisposableBean {

    private static final String ENDPOINT = "/mcp/curator";
    private static final String SERVER_NAME = "agentkb-curator";

    private final McpSyncServer server;

    CuratorMcpEndpoint(@Value("${spring.ai.mcp.server.version}") String version) {
        super(HttpServletStreamableServerTransportProvider.builder().mcpEndpoint(ENDPOINT).build(), ENDPOINT);
        this.server = McpServer.sync(getServlet())
                .serverInfo(SERVER_NAME, version)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(curatorPing())
                .build();
    }

    private static SyncToolSpecification curatorPing() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder("cur_ping", Map.of("type", "object"))
                        .description("Checks that the curator MCP endpoint is alive.")
                        .build())
                .callHandler((exchange, request) -> CallToolResult.builder()
                        .content(List.of(new TextContent("cur_pong")))
                        .build())
                .build();
    }

    @Override
    public void destroy() {
        server.close();
    }
}
