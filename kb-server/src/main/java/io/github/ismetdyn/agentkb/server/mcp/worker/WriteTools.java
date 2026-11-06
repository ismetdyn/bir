package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.signals.PromptRing;
import io.github.ismetdyn.agentkb.server.write.ProposalRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteRequest;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import io.github.ismetdyn.agentkb.server.write.WriteResult;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * kb_remember and kb_propose (docs/MCP-TOOLS.md). They write through WriteService only (invariant
 * 1), so they are announced as writing but not destructive: a write never deletes, it closes and
 * supersedes (invariant 5). They never throw: a rejected or failed write answers {@code {error,
 * hint}}.
 */
@Component
public class WriteTools {

    private static final Logger LOG = LoggerFactory.getLogger(WriteTools.class);

    private static final String USER_STATED = "user_stated";
    private static final Set<String> SOURCE_KINDS = Set.of(USER_STATED, "code", "doc", "command");

    private static final String REMEMBER =
            """
            Store knowledge you just learned, AT THE MOMENT you learn it — right after the user \
            answers your question, or when you understand something from code or docs that is \
            not in the knowledge base. Input: `entity` (id or alias; created if missing), `type` \
            (for new entities), `facts` [{key, value, env?, target?}], `relations` [{type, \
            target, env?}], `source` {kind: `user_stated` | `code` | `doc` | `command`, ref?, \
            evidence?, episode? (the episode id from the briefing; an unknown or missing id is \
            not an error)}. Free-text knowledge goes into facts with text keys such as `role`, \
            `constraint`, `access`, `convention`, or `note` for any short statement that has no \
            key; keep each to one short statement (max 500 characters). An unknown key is \
            rejected with a hint: write it as `note` or propose the key with \
            `kb_propose(kind=schema)`. Store knowledge, not transcripts. For `user_stated`, put the user's \
            exact words, including the value, in `evidence`; if they cannot be matched to one \
            recent user message or lack a fact's value, that fact is stored as unverified. NEVER put secret values here (passwords, tokens, keys): \
            they are rejected. Ask the user to run `kb secret set <entity>/<env>/<name>` instead. \
            Facts that contradict verified knowledge do not overwrite it; they are stored as \
            conflicts and the result tells you. For decisions, rules or deletions use \
            `kb_propose`.""";
    private static final String PROPOSE =
            """
            Proposes a normative change that needs the user's approval: a new or changed \
            decision/rule (with its stack or project), a schema change, archiving or deleting a \
            record. Nothing changes until the user accepts it in the TUI or GUI. Use at the end \
            of a task. Input: `kind` (`decision` | `rule-change` | `archive` | `schema`), \
            `target?` (id of the record it changes; linked with an `ABOUT` edge), `content` \
            (Markdown), `evidence?` (why, and what it is based on), `episode?` (from the \
            briefing). For `kind=schema`, `content` is the new fact key (name, kind, validator); \
            once the user approves it, it enters `schema/facts.yaml`.""";
    private static final String EPISODE =
            "The episode id from the briefing; an unknown or missing id is not an error.";
    // Codex 0.161.0 sends its session id here in tools/call; Claude Code 2.1.318 sends none
    // (ADR-0023 decision 6, measured 2026-11-06).
    private static final String CODEX = "codex";
    private static final String CODEX_SESSION = "sessionId";

    private static final String FAILED = "the write failed; nothing was stored";
    private static final String FAILED_HINT =
            "Try again; if it fails again, check schema/*.yaml in the knowledge base repo.";

    private final WriteService writes;
    private final PromptRing prompts;
    private final KnowledgeQueries queries;

    public WriteTools(WriteService writes, PromptRing prompts, KnowledgeQueries queries) {
        this.writes = writes;
        this.prompts = prompts;
        this.queries = queries;
    }

    public record FactArg(
            @McpToolParam(description = "Fact key from kb_schema, e.g. hostname, ip, role.")
                    String key,
            @McpToolParam(description = "One value or one short statement.") String value,
            @McpToolParam(required = false, description = "Environment, e.g. prod.")
                    @Nullable String env,
            @McpToolParam(required = false, description = "Entity a port points to.")
                    @Nullable String target) {}

    public record RelationArg(
            @McpToolParam(description = "Relation type from kb_schema, e.g. depends_on.")
                    String type,
            @McpToolParam(description = "Id or alias of the other entity.") String target,
            @McpToolParam(required = false, description = "Environment, e.g. prod.")
                    @Nullable String env) {}

    public record SourceArg(
            @McpToolParam(description = "user_stated | code | doc | command") String kind,
            @McpToolParam(required = false, description = "File, document or command.")
                    @Nullable String ref,
            @McpToolParam(required = false, description = "The user's exact words.")
                    @Nullable String evidence,
            @McpToolParam(required = false, description = EPISODE) @Nullable String episode) {}

    @McpTool(
            name = "kb_remember",
            description = REMEMBER,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public String remember(
            @McpToolParam(description = "Entity id or alias; created if missing.")
                    @Nullable String entity,
            @McpToolParam(required = false, description = "Entity type for a new entity.")
                    @Nullable String type,
            @McpToolParam(required = false, description = "Facts to store.")
                    @Nullable List<FactArg> facts,
            @McpToolParam(required = false, description = "Relations to other entities.")
                    @Nullable List<RelationArg> relations,
            @McpToolParam(description = "Where the knowledge comes from.")
                    @Nullable SourceArg source,
            McpMeta meta) {
        return attempt(
                "kb_remember",
                () -> {
                    if (source == null
                            || source.kind() == null
                            || !SOURCE_KINDS.contains(source.kind().strip())) {
                        return Answers.error(
                                "source.kind is missing or unknown",
                                "Pass source {kind: user_stated | code | doc | command}.");
                    }
                    Instant now = Instant.now();
                    Source trusted = trust(source, now);
                    // Only a heard user_stated evidence can leave a value out (SPEC §7 rule 7).
                    String heard = trusted == Source.USER_STATED ? source.evidence() : null;
                    List<FactInput> factInputs = RememberInputs.facts(facts, heard);
                    List<RelationInput> relationInputs = RememberInputs.relations(relations, heard);
                    WriteResult result =
                            writes.write(
                                    new WriteRequest(
                                            trusted,
                                            entity,
                                            type,
                                            factInputs,
                                            relationInputs,
                                            RememberInputs.evidence(source),
                                            null,
                                            episode(heard, now, meta, source.episode()),
                                            now));
                    return stored(result, downgraded(source, trusted, factInputs, relationInputs));
                });
    }

    /** ADR-0023 decision 3: the session the user said it in comes first. */
    private @Nullable String episode(
            @Nullable String heard, Instant now, McpMeta meta, @Nullable String argument) {
        Optional<String> heardIn =
                heard == null
                        ? Optional.empty()
                        : prompts.session(heard, now)
                                .flatMap(
                                        session -> queries.episode(session.client(), session.id()));
        return heardIn.orElseGet(() -> episode(queries, meta, argument));
    }

    /** A user_stated source that was not heard, or holds a value the user did not say. */
    private static boolean downgraded(
            SourceArg source,
            Source trusted,
            List<FactInput> factInputs,
            List<RelationInput> relationInputs) {
        return USER_STATED.equals(source.kind().strip())
                && (trusted == Source.AGENT
                        || factInputs.stream().anyMatch(FactInput::unverified)
                        || relationInputs.stream().anyMatch(RelationInput::unverified));
    }

    @McpTool(
            name = "kb_propose",
            description = PROPOSE,
            annotations =
                    @McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public String propose(
            @McpToolParam(description = "decision | rule-change | archive | schema")
                    @Nullable String kind,
            @McpToolParam(required = false, description = "Id of the record it changes.")
                    @Nullable String target,
            @McpToolParam(description = "The proposed change as Markdown.")
                    @Nullable String content,
            @McpToolParam(required = false, description = "Why, and what it is based on.")
                    @Nullable String evidence,
            @McpToolParam(required = false, description = EPISODE) @Nullable String episode,
            McpMeta meta) {
        return attempt(
                "kb_propose",
                () -> {
                    String id =
                            writes.propose(
                                    new ProposalRequest(
                                            Source.AGENT,
                                            kind,
                                            target,
                                            content,
                                            evidence,
                                            episode(queries, meta, episode)));
                    return "Proposal "
                            + id
                            + " is open. Nothing changes until the user accepts it in the TUI or"
                            + " GUI; tell the user it is waiting.";
                });
    }

    /**
     * ADR-0023 decision 3 without a heard prompt: the Episode of the Codex session named in {@code
     * _meta}, else the argument as given; WriteService ignores one that names no Episode. The
     * client's own session id comes before the model's argument, which it may have mistyped.
     */
    static @Nullable String episode(
            KnowledgeQueries queries, McpMeta meta, @Nullable String argument) {
        Optional<String> codex =
                meta.get(CODEX_SESSION) instanceof String session && !session.isBlank()
                        ? queries.episode(CODEX, session.strip())
                        : Optional.empty();
        return codex.orElse(argument);
    }

    /** SPEC §7 rule 7: user_stated only with the user's own recent words; anything else agent. */
    private Source trust(SourceArg source, Instant now) {
        boolean heard =
                USER_STATED.equals(source.kind().strip())
                        && source.evidence() != null
                        && prompts.contains(source.evidence(), now);
        return heard ? Source.USER_STATED : Source.AGENT;
    }

    private static String stored(WriteResult result, boolean downgraded) {
        StringBuilder reply =
                new StringBuilder(
                        "Stored on "
                                + result.entity()
                                + (result.created() ? " (new entity)" : "")
                                + ": "
                                + result.added().size()
                                + " added, "
                                + result.refreshed().size()
                                + " refreshed, "
                                + result.closed().size()
                                + " closed. Batch "
                                + result.batch()
                                + ".");
        if (!result.conflicts().isEmpty()) {
            reply.append(
                    "\nConflict: "
                            + result.conflicts().size()
                            + " fact(s) contradict more trusted knowledge; they wait with status"
                            + " conflict and the current value is kept. Tell the user if it"
                            + " matters.");
        }
        if (downgraded) {
            reply.append(
                    "\nThe evidence was not found in the user's recent messages, or it does not"
                            + " hold the written value, so those facts are stored as agent"
                            + " knowledge (unverified). Put the user's exact words, value"
                            + " included, in evidence.");
        }
        return reply.toString();
    }

    /**
     * The one place a write tool turns a failure into an answer. A rejection carries its own
     * message and hint, which never hold a value (WriteRejectedException); anything else is logged
     * by exception class only, as its message may hold the request (invariant 10).
     */
    static String attempt(String tool, Supplier<String> body) {
        try {
            return body.get();
        } catch (WriteRejectedException e) {
            return Answers.error(e.getMessage(), e.hint());
        } catch (RuntimeException e) {
            LOG.warn("event=write_tool_failed tool={} error={}", tool, e.getClass().getName());
            return Answers.error(FAILED, FAILED_HINT);
        }
    }
}
