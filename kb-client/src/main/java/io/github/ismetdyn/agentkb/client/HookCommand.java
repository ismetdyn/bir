package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

/**
 * {@code kb hook <event> [claude|codex]} (ADR-0027 decision 4, ARCHITECTURE §11): the hook command
 * Claude Code and Codex run, on Linux and Windows alike. It hands stdin, the hook JSON, to {@code
 * POST /hooks/<event>?client=} and prints the server's answer; the logic is the server's. It never
 * blocks a session: hooks off ({@code KB_HOOKS=off}), an unknown event, an address off this
 * machine, a server that is down, slow or failing all give no output and exit 0 (fail-open), as
 * integrations/kb-hook.sh did with curl.
 */
@Command(
        name = "hook",
        description = "Carry a Claude Code or Codex hook to the local server; always exits 0.")
final class HookCommand implements Callable<Integer> {

    private static final Set<String> EVENTS =
            Set.of("session-start", "user-prompt-submit", "post-tool-use");
    private static final String DEFAULT_CLIENT = "claude";
    // SPEC §12: a hook answers within 2 s (curl --max-time 2 before); the agent drops a later one.
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private static final int SUCCESS_CLASS = 2;
    private static final int STATUS_CLASS = 100;

    @Parameters(
            arity = "0..*",
            paramLabel = "<event> [client]",
            description = "session-start, user-prompt-submit or post-tool-use; claude or codex.")
    List<String> args = new ArrayList<>();

    // A command line kb does not understand is one more reason to stay silent, not to fail.
    @Unmatched List<String> unmatched = new ArrayList<>();

    @Override
    public Integer call() {
        String event = args.isEmpty() ? "" : args.getFirst();
        if ("off".equals(System.getenv("KB_HOOKS")) || !EVENTS.contains(event)) {
            return 0;
        }
        String client = args.size() < 2 || args.get(1).isBlank() ? DEFAULT_CLIENT : args.get(1);
        try {
            System.out.writeBytes(answer(event, client));
            System.out.flush();
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
            // Fail-open is the contract: whatever went wrong, the session goes on without kb.
            // stderr stays empty too, as the agent may show it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /**
     * The server's answer; empty unless it is a 2xx. The address is checked (SecretApi). The budget
     * covers reading stdin too: a client that never closes it gets no answer, not a hung hook.
     */
    private static byte[] answer(String event, String client)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + BUDGET.toNanos();
        byte[] payload =
                CompletableFuture.supplyAsync(HookCommand::stdin)
                        .get(BUDGET.toNanos(), TimeUnit.NANOSECONDS);
        URI uri =
                URI.create(
                        SecretApi.serverUrl()
                                + "/hooks/"
                                + event
                                + "?client="
                                + URLEncoder.encode(client, StandardCharsets.UTF_8));
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .timeout(BUDGET)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
        // No proxy: one from the environment or the JVM options would carry the prompt away.
        HttpClient http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .connectTimeout(BUDGET)
                        .build();
        // The request timeout ends with the headers; the budget covers the body too.
        HttpResponse<byte[]> response =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                        .get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        return response.statusCode() / STATUS_CLASS == SUCCESS_CLASS
                ? response.body()
                : new byte[0];
    }

    // On a pool thread, so the budget can give up on it; System.exit then ends it.
    private static byte[] stdin() {
        try {
            return System.in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
