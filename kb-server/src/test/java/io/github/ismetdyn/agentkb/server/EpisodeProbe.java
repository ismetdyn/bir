package io.github.ismetdyn.agentkb.server;

import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.SESSION_START;
import static io.github.ismetdyn.agentkb.server.hooks.HookHttp.USER_PROMPT_SUBMIT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.graph.EmbeddedNeo4jStore;
import io.github.ismetdyn.agentkb.server.hooks.HookHttp;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads ADR-0023 provenance from the outside: hook calls as kb-hook.sh makes them, and the graph
 * through read-only Cypher. An Episode is {@code (:Episode {id, kind, client, session_id?,
 * transcript_path?, started_at, last_at})}; every node and edge a write opens carries its id in
 * {@code episode}, next to {@code batch}. Nothing here knows how the server does it.
 */
public final class EpisodeProbe {

    /** ARCHITECTURE §4: an Episode id is a ULID. */
    public static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    /** What a write left in {@code episode} when it left nothing. */
    public static final String NONE = "<none>";

    private static final Pattern BATCH = Pattern.compile("Batch (\\S+?)\\.");
    private static final String EPISODE =
            "MATCH (e:Episode {client: $client, session_id: $session}) RETURN properties(e) AS e";
    private static final String STAMPS =
            "MATCH (n {batch: $batch}) RETURN coalesce(n.episode, $none) AS episode"
                    + " UNION ALL MATCH ()-[r {batch: $batch}]->()"
                    + " RETURN coalesce(r.episode, $none) AS episode";

    private final HookHttp hooks;
    private final EmbeddedNeo4jStore store;

    /** A session as SessionStart reported it: its Episode's id and the brief the agent got. */
    public record Session(String client, String sessionId, String episode, String brief) {}

    public EpisodeProbe(int port, EmbeddedNeo4jStore store) {
        this.hooks = new HookHttp(port);
        this.store = store;
    }

    /** A fresh session id, so no other test can share its Episode. */
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** SessionStart for {@code (client, sessionId)}; fails unless it leaves exactly one Episode. */
    public Session start(String client, String sessionId, Path cwd)
            throws IOException, InterruptedException {
        HttpResponse<String> reply =
                hooks.post(
                        SESSION_START,
                        client,
                        HookHttp.sessionStart(client, cwd)
                                .put("session_id", sessionId)
                                .put("transcript_path", transcript(client, sessionId)));
        String brief = HookHttp.context(SESSION_START, reply);
        List<Map<String, Object>> episodes = episodes(client, sessionId);
        assertThat(episodes)
                .as("SessionStart opens one Episode for (%s, %s)", client, sessionId)
                .hasSize(1);
        return new Session(client, sessionId, (String) episodes.getFirst().get("id"), brief);
    }

    /** UserPromptSubmit of {@code prompt} in {@code session}; PromptRing keeps it in RAM. */
    public void prompt(Session session, String prompt) throws IOException, InterruptedException {
        HttpResponse<String> reply =
                hooks.post(
                        USER_PROMPT_SUBMIT,
                        session.client(),
                        HookHttp.userPromptSubmit(session.client(), prompt)
                                .put("session_id", session.sessionId())
                                .put(
                                        "transcript_path",
                                        transcript(session.client(), session.sessionId())));
        HookHttp.context(USER_PROMPT_SUBMIT, reply);
    }

    /** The properties of every Episode of {@code (client, sessionId)}. */
    public List<Map<String, Object>> episodes(String client, String sessionId) {
        return rows(EPISODE, Map.of("client", client, "session", sessionId)).stream()
                .map(row -> props(row.get("e")))
                .toList();
    }

    /** The properties of the Episode {@code id}, if there is one. */
    public Optional<Map<String, Object>> episode(String id) {
        return rows("MATCH (e:Episode {id: $id}) RETURN properties(e) AS e", Map.of("id", id))
                .stream()
                .map(row -> props(row.get("e")))
                .findFirst();
    }

    /**
     * The {@code episode} of every node and edge {@code batch} opened, {@link #NONE} where it has
     * none. One entry per record, so an empty list means the batch opened nothing.
     */
    public List<String> stamps(String batch) {
        return rows(STAMPS, Map.of("batch", batch, "none", NONE)).stream()
                .map(row -> (String) row.get("episode"))
                .toList();
    }

    /** The batch a write tool reported: {@code … Batch <id>.} */
    public static String batch(String reply) {
        Matcher batch = BATCH.matcher(reply);
        assertThat(batch.find()).as("a batch in %s", reply).isTrue();
        return batch.group(1);
    }

    public List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        return store.read(tx -> tx.execute(cypher, params).stream().toList());
    }

    /** The transcript path the client would send; not a file on this machine. */
    public static String transcript(String client, String sessionId) {
        return "/home/user/." + client + "/sessions/kbtest-" + sessionId + ".jsonl";
    }

    private static Map<String, Object> props(Object value) {
        Map<String, Object> props = new TreeMap<>();
        ((Map<?, ?>) value).forEach((key, prop) -> props.put((String) key, prop));
        return props;
    }
}
