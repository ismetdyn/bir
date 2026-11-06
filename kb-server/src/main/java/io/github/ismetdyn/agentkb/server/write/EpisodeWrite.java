package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.secret.SecretPatterns;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;

/**
 * The Episode of ADR-0023: the session or event a write came from. It holds no content (no prompt
 * or answer text, ADR-0017) and no batch, so {@code kb revert --batch} never deletes it (decision
 * 2). It is not local: it goes into the snapshot like any record (decision 4).
 */
public final class EpisodeWrite {

    static final String LABEL = "Episode";
    static final String SESSION = "session";
    static final String SCAN = "scan";
    static final String USER = "user";
    static final String KB = "kb";

    private static final Label EPISODE = Label.label(LABEL);
    private static final String KIND = "kind";
    private static final String CLIENT = "client";
    private static final String SESSION_ID = "session_id";
    private static final String TRANSCRIPT_PATH = "transcript_path";
    private static final String STARTED_AT = "started_at";
    private static final String LAST_AT = "last_at";
    // Claude Code and Codex send UUIDs (measured 2026-11-06); anything else is not kept.
    private static final Pattern SESSION_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int PATH_LIMIT = 1_024;

    private EpisodeWrite() {}

    /**
     * The stripped {@code sessionId} if it is a plain short id, the only kind an Episode is opened
     * for; empty otherwise, also when it looks like a secret (invariant 2). Also the boundary check
     * of the hook's session (checklist A1).
     */
    public static Optional<String> sessionId(String sessionId) {
        String id = sessionId.strip();
        return SESSION_PATTERN.matcher(id).matches() && !SecretPatterns.looksLikeSecret(id)
                ? Optional.of(id)
                : Optional.empty();
    }

    /**
     * {@code transcriptPath} if it is plain short text that does not look like a secret; null
     * otherwise, so it is left out.
     */
    static @Nullable String transcriptPath(@Nullable String transcriptPath) {
        return transcriptPath == null
                        || transcriptPath.isBlank()
                        || transcriptPath.length() > PATH_LIMIT
                        || transcriptPath.chars().anyMatch(Character::isISOControl)
                        || SecretPatterns.looksLikeSecret(transcriptPath)
                ? null
                : transcriptPath;
    }

    /** {@code id} if it names an Episode; anything else is no episode, never an error. */
    static Optional<String> known(Transaction tx, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String stripped = id.strip();
        return tx.findNode(EPISODE, GraphWrite.ID, stripped) == null
                ? Optional.empty()
                : Optional.of(stripped);
    }

    /**
     * The props of an Episode of an event without a session (a scan, a REST write of the user),
     * which GraphWrite opens with the first record it stamps.
     */
    static Map<String, Object> event(String kind, String client, String at) {
        return props(kind, client, at);
    }

    /**
     * The Episode of {@code (client, sessionId)}: its last_at refreshed, or opened with the
     * transcript path the client sent.
     */
    static String session(
            GraphWrite graph,
            String client,
            String sessionId,
            @Nullable String transcriptPath,
            String at) {
        try (ResourceIterator<Node> found =
                graph.tx.findNodes(EPISODE, CLIENT, client, SESSION_ID, sessionId)) {
            if (found.hasNext()) {
                Node episode = found.next();
                graph.set(episode, LAST_AT, at);
                return (String) episode.getProperty(GraphWrite.ID);
            }
        }
        String id = Ulid.next();
        Map<String, Object> props = props(SESSION, client, at);
        props.put(SESSION_ID, sessionId);
        if (transcriptPath != null) {
            props.put(TRANSCRIPT_PATH, transcriptPath);
        }
        graph.localNode(id, props, LABEL);
        return id;
    }

    private static Map<String, Object> props(String kind, String client, String at) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(KIND, kind);
        props.put(CLIENT, client);
        props.put(STARTED_AT, at);
        props.put(LAST_AT, at);
        return props;
    }
}
