package io.github.ismetdyn.agentkb.server.mcp.worker;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
public class PingTool {

    @McpTool(name = "ping", description = "Checks that the agent MCP endpoint is alive.")
    public String ping() {
        return "pong";
    }
}
