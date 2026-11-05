package io.github.ismetdyn.agentkb.server.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.QueryExecutionType.QueryType;
import org.neo4j.graphdb.Result;
import org.springframework.stereotype.Component;

/**
 * Invariant 7 and SPEC §13.5: which queries kb_cypher may run. Only reads; no LOAD CSV, procedure,
 * admin command or batch; no {@code secret_value} in any form, nor a property read by a computed
 * name, which builds that name (G6 probe); relation types only from schema/relations.yaml and the
 * fixed edges. The store's read-only transaction and the row filter of {@link
 * KnowledgeQueries#cypher} stay behind it.
 */
@Component
public class CypherGuard {

    /** security.md: every entry point has a limit; a real query is a few lines. */
    public static final int MAX_QUERY_CHARS = 10_000;

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern SECRET_VALUE =
            Pattern.compile("secret_value", Pattern.CASE_INSENSITIVE);
    private static final String EXPLAIN = "EXPLAIN ";
    // Checked on the masked text, so a word in a string or a comment does not count; a property
    // or parameter of that name ({@code r.set}, {@code $create}) is not a clause.
    private static final List<Rule> RULES =
            List.of(
                    // Neo4j reads Unicode spaces and some control characters as whitespace (G6
                    // probe), the rules below only ASCII; outside strings and comments a query
                    // needs neither.
                    rule(
                            "[\\p{Cc}\\p{Cf}\\p{Z}&&[^ \\t\\n\\r]]",
                            "use plain spaces and line breaks between words"),
                    rule(";", "one statement at a time, without ';'"),
                    rule("\\b(?:EXPLAIN|PROFILE)\\b", "send the query without EXPLAIN or PROFILE"),
                    rule("\\bLOAD\\s+CSV\\b", "LOAD CSV is not allowed"),
                    rule(
                            "\\bTRANSACTIONS\\b|\\bPERIODIC\\s+COMMIT\\b",
                            "batched transactions are not allowed"),
                    rule(
                            "(?<![.$])\\b(?:CREATE|MERGE|SET|DELETE|DETACH|REMOVE|FOREACH|DROP)\\b",
                            "only read queries are allowed"),
                    rule("\\bCALL\\s*(?=[\\p{L}_`])", "procedures are not allowed"),
                    rule("(?<![.$])\\b(?:SHOW|USE)\\b", "admin commands are not allowed"));

    private final EmbeddedNeo4jStore store;
    private final RepoSchema schema;

    public CypherGuard(EmbeddedNeo4jStore store, RepoSchema schema) {
        this.store = store;
        this.schema = schema;
    }

    /**
     * Why the query may not run, empty if it may. The reason is for the agent: it names the rule,
     * never the query or an internal error.
     */
    public Optional<String> check(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return Optional.of("the query is empty");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            return Optional.of("the query is longer than " + MAX_QUERY_CHARS + " characters");
        }
        if (SECRET_VALUE.matcher(unescape(query)).find()) {
            return Optional.of("secret values are never readable");
        }
        String masked = CypherText.mask(query);
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(masked).find()) {
                return Optional.of(rule.reason());
            }
        }
        if (CypherText.hasDynamicLookup(masked)) {
            return Optional.of("a property is read by name (n.name); an index must be a number");
        }
        return relationTypes(masked).or(() -> explain(query));
    }

    private Optional<String> relationTypes(String masked) {
        Set<String> allowed;
        try {
            allowed = new HashSet<>(schema.load().relationTypes());
        } catch (IllegalStateException e) {
            return Optional.of("the knowledge base has no readable schema");
        }
        allowed.addAll(Schema.FIXED_EDGE_TYPES);
        return allowed.containsAll(CypherText.relationTypes(masked))
                ? Optional.empty()
                : Optional.of("unknown relation type");
    }

    // Planning without running: a syntax error or anything but a read is refused.
    private Optional<String> explain(String query) {
        try {
            QueryType type =
                    store.read(
                            tx -> {
                                try (Result plan = tx.execute(EXPLAIN + query)) {
                                    return plan.getQueryExecutionType().queryType();
                                }
                            });
            return type == QueryType.READ_ONLY
                    ? Optional.empty()
                    : Optional.of("only read queries are allowed");
        } catch (RuntimeException e) {
            return Optional.of("the query is not valid Cypher");
        } catch (StackOverflowError e) {
            // Deep nesting overflows the Cypher parser (G6 probe); the thread's stack is free
            // again once the error has unwound to here.
            return Optional.of("the query nests too deeply");
        }
    }

    private static String unescape(String query) {
        Matcher escape = UNICODE_ESCAPE.matcher(query);
        return escape.replaceAll(
                match ->
                        Matcher.quoteReplacement(
                                String.valueOf((char) Integer.parseInt(match.group(1), 16))));
    }

    private static Rule rule(String regex, String reason) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), reason);
    }

    private record Rule(Pattern pattern, String reason) {}
}
