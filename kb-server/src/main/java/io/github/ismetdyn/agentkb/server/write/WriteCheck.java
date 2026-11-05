package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Ids;
import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import io.github.ismetdyn.agentkb.server.graph.Schema;
import io.github.ismetdyn.agentkb.server.graph.Schema.FactKey;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * ARCHITECTURE §5 step 1 and SPEC §7 rules 8 and 10 for what can be checked without the graph:
 * schema, key vocabulary, text limits and secret-looking values. Only new MCP and REST writes come
 * here; import is lossless (invariant 11). Messages name a position, never a value (invariant 2).
 */
final class WriteCheck {

    // SPEC §6.1: a text fact is at most 500 characters. Applied to every value: no other key
    // needs more, and a value is shown on one line.
    static final int VALUE_LIMIT = 500;
    // SPEC §6.1 body limit; evidence and proposal content are Markdown of that size at most.
    static final int BODY_LIMIT = 4_000;
    // An id is a short name (ornek-app-01); this only stops a paragraph posing as one.
    static final int ID_LIMIT = 200;
    // ADR-0022 decision 31: facts and relations of one write, each counted on its own.
    static final int ITEM_LIMIT = 50;

    static final String SECRET_HINT =
            "Never store secret values here. Ask the user to run kb secret set"
                    + " <entity>/<env>/<name>; then refer to it as kb:secret/<entity>/<env>/<name>.";
    static final String TOO_LONG_HINT =
            "Keep a fact to one short statement of at most 500 characters; add long text as a"
                    + " document with kb doc add.";
    private static final String KEY_HINT =
            "Use a fact key listed by kb_schema; free text goes into a text key such as role,"
                    + " constraint, access or convention.";
    private static final String TYPE_HINT =
            "Use an entity type listed by kb_schema, e.g. server, database, service or project.";
    private static final String RELATION_HINT =
            "Use a relation type listed by kb_schema, e.g. depends_on, runs, deployed_on or"
                    + " part_of.";
    private static final String TARGET_KEY_HINT =
            "Only fact keys marked with target in kb_schema (port) point to an entity.";
    private static final String INPUT_HINT =
            "Give an entity id, and each fact a key and a value, each relation a type and a target.";

    private WriteCheck() {}

    record CheckedFact(
            String key,
            String value,
            String env,
            @Nullable String target,
            boolean multi,
            Source source) {}

    record CheckedRelation(String type, String target, @Nullable String env, Source source) {}

    /** {@code label} is the type label for a new entity, null without a type. */
    record Checked(
            @Nullable String label, List<CheckedFact> facts, List<CheckedRelation> relations) {

        Set<String> relationTypes() {
            Set<String> types = new TreeSet<>();
            relations.forEach(relation -> types.add(relation.type()));
            return types;
        }
    }

    static Checked check(WriteRequest request, Schema schema) {
        checkRequest(request);
        String label = isBlank(request.type()) ? null : label(schema, request.type());
        List<CheckedFact> facts = new ArrayList<>();
        for (int i = 0; i < request.facts().size(); i++) {
            FactInput input = request.facts().get(i);
            facts.add(fact(schema, input, "fact " + (i + 1), source(request, input.unverified())));
        }
        List<CheckedRelation> relations = new ArrayList<>();
        for (int i = 0; i < request.relations().size(); i++) {
            RelationInput input = request.relations().get(i);
            relations.add(
                    relation(
                            schema,
                            input,
                            "relation " + (i + 1),
                            source(request, input.unverified())));
        }
        return new Checked(label, facts, relations);
    }

    private static void checkRequest(WriteRequest request) {
        if (request.source() == Source.CURATOR) {
            throw new WriteRejectedException(
                    "the curator cannot write facts or relations",
                    "The curator changes records only through its own tools (docs/CURATOR.md).");
        }
        text("entity", request.entity(), ID_LIMIT);
        if (request.facts().isEmpty() && request.relations().isEmpty()) {
            throw new WriteRejectedException(
                    "nothing to write", "Pass at least one fact or one relation.");
        }
        checkSize(request);
        if (request.evidence() != null) {
            text("evidence", request.evidence(), BODY_LIMIT);
        }
    }

    private static void checkSize(WriteRequest request) {
        if (request.facts().size() > ITEM_LIMIT || request.relations().size() > ITEM_LIMIT) {
            throw new WriteRejectedException(
                    "more than "
                            + ITEM_LIMIT
                            + " facts or "
                            + ITEM_LIMIT
                            + " relations in one write",
                    "Split it into several writes of at most "
                            + ITEM_LIMIT
                            + " facts and "
                            + ITEM_LIMIT
                            + " relations each.");
        }
    }

    /** Blank, too long or secret-looking text is rejected; the value is never echoed. */
    static String text(String what, @Nullable String value, int limit) {
        if (isBlank(value)) {
            throw new WriteRejectedException(what + " is empty", INPUT_HINT);
        }
        if (value.codePointCount(0, value.length()) > limit) {
            throw new WriteRejectedException(
                    what + " is longer than " + limit + " characters", TOO_LONG_HINT);
        }
        if (SecretPatterns.looksLikeSecret(value)) {
            throw new WriteRejectedException(what + " looks like a secret value", SECRET_HINT);
        }
        return value.strip();
    }

    static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    static String label(Schema schema, String type) {
        String wanted = Ids.normalize(type.strip());
        return schema.entityTypes().stream()
                .filter(
                        known ->
                                Ids.normalize(known.key()).equals(wanted)
                                        || Ids.normalize(known.label()).equals(wanted))
                .map(Schema.EntityType::label)
                .findFirst()
                .orElseThrow(() -> new WriteRejectedException("unknown entity type", TYPE_HINT));
    }

    /** SPEC §7 rule 7: an item whose value the user_stated evidence lacks is agent knowledge. */
    private static Source source(WriteRequest request, boolean unverified) {
        return unverified ? Source.AGENT : request.source();
    }

    private static CheckedFact fact(Schema schema, FactInput input, String where, Source source) {
        if (isBlank(input.key())) {
            throw new WriteRejectedException(where + ": key is empty", INPUT_HINT);
        }
        FactKey key =
                schema.factKeys().stream()
                        .filter(known -> known.key().equals(input.key().strip()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new WriteRejectedException(
                                                where + ": unknown key", KEY_HINT));
        String value = text(where + ": value", input.value(), VALUE_LIMIT);
        FactValues.check(key, value, where);
        if (input.target() != null && !key.rules().containsKey("target")) {
            throw new WriteRejectedException(where + ": key takes no target", TARGET_KEY_HINT);
        }
        String target =
                input.target() == null ? null : text(where + ": target", input.target(), ID_LIMIT);
        boolean multi =
                "true".equals(key.rules().get("multi")) || "text".equals(key.rules().get("kind"));
        return new CheckedFact(key.key(), value, env(input.env(), where), target, multi, source);
    }

    private static CheckedRelation relation(
            Schema schema, RelationInput input, String where, Source source) {
        if (isBlank(input.type())) {
            throw new WriteRejectedException(where + ": type is empty", INPUT_HINT);
        }
        String type =
                schema.relationType(input.type())
                        .orElseThrow(
                                () ->
                                        new WriteRejectedException(
                                                where + ": unknown relation type", RELATION_HINT));
        String target = text(where + ": target", input.target(), ID_LIMIT);
        String env = isBlank(input.env()) ? null : env(input.env(), where);
        return new CheckedRelation(type, target, env, source);
    }

    // One form per env, as for ids, so "Prod" and "prod" are one slot (Fact.DEFAULT_ENV if none).
    private static String env(@Nullable String env, String where) {
        return isBlank(env)
                ? Fact.DEFAULT_ENV
                : Ids.normalize(text(where + ": env", env, ID_LIMIT));
    }
}
