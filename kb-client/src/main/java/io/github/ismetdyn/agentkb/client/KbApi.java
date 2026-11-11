package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * The REST calls of kb search, edit, fact set, rel add, archive and unarchive and of {@code kb tui}
 * as kb-api records (docs/CLIENTS.md §1): one place builds each request, the server checks and
 * refuses. Every call goes through JsonApi, so its text comes back without control characters and a
 * refusal is a KbException with the server's error and hint. No call here reaches a secret value
 * (invariant 2).
 */
public final class KbApi {

    private static final String ENTITY = "/api/v1/entities/";

    private final JsonApi api;

    /** How long kb tui waits for an answer: a screen must not hang for minutes. */
    public static final Duration INTERACTIVE = Duration.ofSeconds(10);

    /**
     * The server at {@code base}; a call that takes longer than {@code timeout} fails.
     *
     * @throws KbException if {@code base} does not name this machine, as for AGENTKB_URL
     */
    public KbApi(String base, Duration timeout) {
        this.api = new JsonApi(SecretApi.loopbackBase(base), timeout, null);
    }

    /**
     * The server of {@code AGENTKB_URL}, which must name this machine (SecretApi.serverUrl): the
     * CLI waits {@link JsonApi#REQUEST_TIMEOUT}, kb tui {@link #INTERACTIVE}.
     */
    public static KbApi fromEnvironment(Duration timeout) {
        return new KbApi(SecretApi.serverUrl(), timeout);
    }

    public SchemaAnswer schema() {
        return Json.toRecord(api.get("/api/v1/schema"), SchemaAnswer.class);
    }

    /** {@code GET /entities}: every entity, of {@code type} if given. */
    public EntityListAnswer entities(@Nullable String type, int limit) {
        return Json.toRecord(
                api.get(RequestText.query("/api/v1/entities", "type", type, "limit", limit)),
                EntityListAnswer.class);
    }

    public SearchAnswer search(
            String query, @Nullable String type, @Nullable String env, @Nullable Integer limit) {
        return Json.toRecord(
                api.get(
                        RequestText.query(
                                "/api/v1/search",
                                "q",
                                query,
                                "type",
                                type,
                                "env",
                                env,
                                "limit",
                                limit)),
                SearchAnswer.class);
    }

    /**
     * The JSON view of an entity (ADR-0025 decision 13; no Accept header is JSON). Read with get,
     * not text: text strips control characters before JSON decodes an escaped one, get after.
     */
    public EntityAnswer entity(String id) {
        return Json.toRecord(api.get(ENTITY + RequestText.segment(id)), EntityAnswer.class);
    }

    /**
     * {@code PATCH /entities/{id}}: {@code aliases} replace the list, a null one or summary is left
     * as it is.
     */
    public EditAnswer edit(String id, @Nullable List<String> aliases, @Nullable String summary) {
        StringJoiner body = new StringJoiner(", ", "{", "}");
        if (aliases != null) {
            StringJoiner names = new StringJoiner(", ", "[", "]");
            aliases.forEach(alias -> names.add(RequestText.quote(alias)));
            body.add("\"aliases\": " + names);
        }
        if (summary != null) {
            body.add("\"summary\": " + RequestText.quote(summary));
        }
        return Json.toRecord(
                api.patch(ENTITY + RequestText.segment(id), body.toString()), EditAnswer.class);
    }

    /** One fact; a different current value of the key is closed by the server. */
    public WriteAnswer setFact(String id, String key, String value, @Nullable String env) {
        String fact =
                "{\"key\": "
                        + RequestText.quote(key)
                        + ", \"value\": "
                        + RequestText.quote(value)
                        + (env == null ? "" : ", \"env\": " + RequestText.quote(env))
                        + "}";
        return Json.toRecord(
                api.post(
                        ENTITY + RequestText.segment(id) + "/facts", "{\"facts\": [" + fact + "]}"),
                WriteAnswer.class);
    }

    public WriteAnswer addRelation(String id, String type, String target) {
        String relation =
                "{\"type\": "
                        + RequestText.quote(type)
                        + ", \"target\": "
                        + RequestText.quote(target)
                        + "}";
        return Json.toRecord(
                api.post(
                        ENTITY + RequestText.segment(id) + "/relations",
                        "{\"relations\": [" + relation + "]}"),
                WriteAnswer.class);
    }

    public EditAnswer archive(String id) {
        return Json.toRecord(
                api.post(ENTITY + RequestText.segment(id) + "/archive", "{}"), EditAnswer.class);
    }

    public EditAnswer unarchive(String id) {
        return Json.toRecord(
                api.post(ENTITY + RequestText.segment(id) + "/unarchive", "{}"), EditAnswer.class);
    }
}
