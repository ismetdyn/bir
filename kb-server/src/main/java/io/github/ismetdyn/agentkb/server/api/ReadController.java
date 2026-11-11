package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.core.render.MarkdownView;
import io.github.ismetdyn.agentkb.server.graph.CypherGuard;
import io.github.ismetdyn.agentkb.server.graph.KnowledgeQueries;
import io.github.ismetdyn.agentkb.server.graph.ListQueries;
import io.github.ismetdyn.agentkb.server.graph.RepoSchema;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reads of docs/CLIENTS.md §1: {@code GET /api/v1/search?q=&type=&env=&limit=} (at most 20,
 * ADR-0017; ordered and marked, invariant 12), {@code GET /api/v1/entities/{id}} as Markdown from
 * MarkdownView, the one renderer, for {@code Accept: text/markdown} and else as JSON (ADR-0025
 * decision 13), {@code POST /api/v1/cypher {query}} through CypherGuard with kb_cypher's limits,
 * {@code GET /api/v1/stale?days=&limit=}, and for the TUI and GUI {@code GET /api/v1/schema} (the
 * vocabulary kb_schema reads) and {@code GET /api/v1/entities?type=&limit=} (every entity without a
 * search, archived last; 50 by default, 500 at most). A refusal is {@code {error, hint}} without
 * the request value.
 */
@RestController
class ReadController {

    record CypherRequest(@Nullable String query) {}

    private static final int SEARCH_DEFAULT = 8;
    private static final int SEARCH_MAX = 20;
    // kb_search's limit: a search is a few words, and Lucene caps its clauses.
    private static final int QUERY_CHARS = 500;
    // An id is a short name; WriteCheck takes at most 200 characters for one.
    private static final int WORD_CHARS = 200;
    private static final int STALE_DAYS = 90;
    // The longest age kb stale takes: a fact older than ~27 years is stale at any setting.
    private static final int STALE_MAX_DAYS = 10_000;
    // ADR-0017 decision 4: kb_cypher answers at most 8,000 characters.
    private static final int CYPHER_CHARS = 8_000;
    private static final MediaType MARKDOWN =
            new MediaType(MediaType.TEXT_MARKDOWN, StandardCharsets.UTF_8);
    private static final String CYPHER_HINT =
            "Send one read-only query; kb_schema lists the labels, relation types and example"
                    + " queries.";

    private final KnowledgeQueries queries;
    private final ListQueries lists;
    private final CypherGuard guard;
    private final RepoSchema schema;

    ReadController(
            KnowledgeQueries queries, ListQueries lists, CypherGuard guard, RepoSchema schema) {
        this.queries = queries;
        this.lists = lists;
        this.guard = guard;
        this.schema = schema;
    }

    @GetMapping("/api/v1/schema")
    SchemaAnswer schema() {
        return ReadAnswers.schema(schema.load());
    }

    @GetMapping("/api/v1/entities")
    EntityListAnswer entities(
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String limit) {
        int shown = RequestChecks.listLimit(limit);
        return ListAnswers.entities(
                lists.entities(RequestChecks.word(type, "type", WORD_CHARS)), shown);
    }

    @GetMapping("/api/v1/search")
    SearchAnswer search(
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String env,
            @RequestParam(required = false) @Nullable String limit) {
        String words = RequestChecks.word(q, "q", QUERY_CHARS);
        if (words == null) {
            throw new WriteRejectedException("q is empty", "Pass a few words to search for.");
        }
        int shown = RequestChecks.number(limit, "limit", SEARCH_DEFAULT, 1, SEARCH_MAX);
        return ReadAnswers.hits(
                queries.search(
                        words,
                        RequestChecks.word(type, "type", WORD_CHARS),
                        RequestChecks.word(env, "env", WORD_CHARS)),
                shown);
    }

    @GetMapping("/api/v1/entities/{id}")
    ResponseEntity<?> entity(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) @Nullable String accept) {
        Optional<ResponseEntity<?>> found =
                queries.entity(id)
                        .map(
                                view ->
                                        RequestChecks.wantsMarkdown(accept)
                                                ? ResponseEntity.ok()
                                                        .contentType(MARKDOWN)
                                                        .body(MarkdownView.render(view))
                                                : ResponseEntity.ok()
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .body(ReadAnswers.entity(view)));
        // The type is set, so a Markdown request still gets the JSON refusal.
        return found.orElseGet(
                () ->
                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        Map.of(
                                                "error",
                                                "no entity with this id or alias",
                                                "hint",
                                                "Find the id with kb find; kb show takes an id or"
                                                        + " alias.")));
    }

    @PostMapping("/api/v1/cypher")
    CypherAnswer cypher(@RequestBody CypherRequest request) {
        Optional<String> refused = guard.check(request.query());
        if (refused.isPresent()) {
            throw new WriteRejectedException(refused.get(), CYPHER_HINT);
        }
        String query = request.query();
        return ReadAnswers.cypher(
                RequestChecks.userQuery(() -> queries.cypher(query)),
                KnowledgeQueries.CYPHER_ROWS,
                CYPHER_CHARS);
    }

    @GetMapping("/api/v1/stale")
    SearchAnswer stale(
            @RequestParam(required = false) @Nullable String days,
            @RequestParam(required = false) @Nullable String limit) {
        int age = RequestChecks.number(days, "days", STALE_DAYS, 0, STALE_MAX_DAYS);
        int shown = RequestChecks.listLimit(limit);
        Instant before = Instant.now().minus(Duration.ofDays(age));
        return ReadAnswers.hits(queries.stale(schema.load().operationalLabels(), before), shown);
    }
}
