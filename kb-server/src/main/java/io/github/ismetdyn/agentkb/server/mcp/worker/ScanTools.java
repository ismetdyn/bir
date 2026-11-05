package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.server.scan.ScannerService;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * kb_scan (docs/MCP-TOOLS.md). It writes through WriteService with source scan and never deletes,
 * so it is announced as writing but not destructive. The scanner checks the path (invariant 9); the
 * report names ids, placeholders and file:line, never a secret value (invariant 2). It never
 * throws: a refused or failed scan answers {@code {error, hint}}.
 */
@Component
public class ScanTools {

    private static final String SCAN =
            """
            Extracts infrastructure facts from a repository on this machine WITHOUT reading files \
            into your context: hosts, IPs, ports, datasource URLs, environments (Spring profiles, \
            .env, docker-compose, Kubernetes, Ansible, build files). Secret values found stay on \
            this machine; you get placeholders to use with `kb run`. Call it when you start \
            working in a repository the briefing says was never scanned or has changed, instead \
            of grepping config files for hosts and passwords. Input: `path` (absolute). Returns a \
            summary of new entities, facts, conflicts and findings.""";
    private static final String PATH_HINT =
            "Pass path: the absolute path of a git working copy under agentkb.scan.allowed-roots.";

    private final ScannerService scanner;

    public ScanTools(ScannerService scanner) {
        this.scanner = scanner;
    }

    @McpTool(
            name = "kb_scan",
            description = SCAN,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public String scan(
            // Not required in the schema, so a call without it reaches the {error, hint} answer.
            @McpToolParam(
                            required = false,
                            description = "Absolute path of the repository to scan.")
                    @Nullable String path) {
        return WriteTools.attempt(
                "kb_scan",
                () -> {
                    if (path == null || path.isBlank()) {
                        return Answers.error("path is missing", PATH_HINT);
                    }
                    Path dir;
                    try {
                        dir = Path.of(path.strip());
                    } catch (InvalidPathException e) {
                        return Answers.error("path is not a valid path", PATH_HINT);
                    }
                    return Answers.content(report(scanner.scan(dir)));
                });
    }

    private static String report(ScanResult result) {
        StringBuilder report =
                new StringBuilder(
                        "Scanned "
                                + result.project()
                                + " (repo "
                                + result.repo()
                                + "): "
                                + result.files()
                                + " file(s) with infrastructure facts or secrets; facts "
                                + result.added()
                                + " added, "
                                + result.refreshed()
                                + " refreshed, "
                                + result.closed()
                                + " closed. Batch "
                                + result.batch()
                                + ".\n");
        section(report, "New entities", result.newEntities());
        section(
                report,
                "Secrets (the values stay on this machine; use them with kb run --env"
                        + " NAME=<placeholder>)",
                result.secrets().stream().map(id -> "kb:secret/" + id).toList());
        section(report, "Findings", result.findings());
        section(report, "Not written", result.skipped());
        if (result.conflicts() > 0) {
            report.append(
                    "Conflict: "
                            + result.conflicts()
                            + " scanned value(s) contradict more trusted knowledge; they wait with"
                            + " status conflict. Tell the user if it matters.\n");
        }
        report.append("Read an entity with kb_read.");
        return report.toString();
    }

    private static void section(StringBuilder report, String title, List<String> lines) {
        if (!lines.isEmpty()) {
            report.append(title).append(":\n");
            lines.forEach(line -> report.append("- ").append(line).append('\n'));
        }
    }
}
