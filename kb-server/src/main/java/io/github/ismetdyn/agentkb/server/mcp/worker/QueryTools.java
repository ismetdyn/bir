package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.core.render.ResultList;
import io.github.ismetdyn.agentkb.server.graph.CypherGuard;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * kb_cypher, kb_schema and kb_stale (docs/MCP-TOOLS.md). Like {@link ReadTools}, announced
 * read-only (ADR-0018) and never throwing.
 */
@Component
public class QueryTools {

    private static final int DEFAULT_DAYS = 90;
    // kb_stale lists every fact it finds; the character budget is its only limit.
    private static final int ALL = Integer.MAX_VALUE;

    private static final String CYPHER =
            """
            Runs a READ-ONLY Cypher query. Call `kb_schema` first for labels, relation types, \
            fact keys and example queries. Entities have the label `:Entity` plus a type label \
            (`:Server`, `:Database`, ...); facts are `(:Entity)-[:HAS_FACT]->(:Fact {key, value, \
            env, source, valid_from, observed_at, valid_to, status})`; current facts have \
            `valid_to IS NULL`; `valid_from` is when a value was first seen, `observed_at` when \
            it was last seen. Writes, LOAD CSV, admin procedures and any access to \
            `secret_value` are rejected. Timeout 5 s, max 200 rows.""";
    private static final String SCHEMA =
            """
            Labels, relation types with meanings, fact keys, decision fields and example Cypher \
            queries for this knowledge base.""";
    private static final String STALE =
            """
            Facts that need re-verification: `observed_at` older than `days` (default 90), \
            `unverified` or `conflict`. Use before operational changes.""";

    private static final String CYPHER_HINT =
            "kb_cypher runs one read-only query; kb_schema lists the labels, relation types and"
                    + " example queries.";
    private static final String SCHEMA_FAILED = "the schema of the knowledge base is unreadable";
    private static final String SCHEMA_HINT =
            "Check schema/*.yaml in the knowledge base repo; kb init writes them.";

    private final KnowledgeQueries queries;
    private final CypherGuard guard;
    private final RepoSchema schema;
    private final String examples;

    public QueryTools(KnowledgeQueries queries, CypherGuard guard, RepoSchema schema) {
        this.queries = queries;
        this.guard = guard;
        this.schema = schema;
        this.examples = SchemaView.examples();
    }

    @McpTool(
            name = "kb_cypher",
            description = CYPHER,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public String cypher(
            @McpToolParam(description = "A read-only Cypher query.") @Nullable String query) {
        return Answers.attempt(
                "kb_cypher",
                "the query failed or ran over 5 s",
                "Add LIMIT, narrow the pattern, and write values in place of $parameters.",
                () -> {
                    Optional<String> refused = guard.check(query);
                    if (refused.isPresent()) {
                        return Answers.error(refused.get(), CYPHER_HINT);
                    }
                    return Answers.content(
                            CypherTable.render(
                                    queries.cypher(query),
                                    KnowledgeQueries.CYPHER_ROWS,
                                    Answers.LIST_BUDGET));
                });
    }

    @McpTool(
            name = "kb_schema",
            description = SCHEMA,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public String schema() {
        return Answers.attempt(
                "kb_schema",
                SCHEMA_FAILED,
                SCHEMA_HINT,
                () -> Answers.content(SchemaView.render(schema.load(), examples)));
    }

    @McpTool(
            name = "kb_stale",
            description = STALE,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public String stale(
            @McpToolParam(
                            required = false,
                            description = "Age in days after which a fact is stale (default 90).")
                    @Nullable Integer days) {
        int age = days == null ? DEFAULT_DAYS : days;
        if (age < 0) {
            return Answers.error("days is negative", "Pass 0 or more days (default 90).");
        }
        return Answers.attempt(
                "kb_stale",
                "the read failed",
                "Try again; if it fails again, check schema/*.yaml in the knowledge base repo.",
                () -> {
                    Instant before = Instant.now().minus(Duration.ofDays(age));
                    return Answers.content(
                            ResultList.render(
                                    queries.stale(schema.load().operationalLabels(), before),
                                    ALL,
                                    Answers.LIST_BUDGET));
                });
    }
}
