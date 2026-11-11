package io.github.ismetdyn.agentkb.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One row per kb command of plan.md H6 (docs/CLIENTS.md §2): the arguments, the one request it must
 * send (method, raw path, raw query, body fields) and the lines a person must see for the contract
 * answer under fixtures/api/. kb only calls the server; the server decides (ARCHITECTURE §1).
 */
final class KbCommandCases {

    static final String BATCH = "01KBTESTBATCH0000000000000Z";
    static final String MARKDOWN = "# ornek-app-01 (Server)\n\n- ip @prod: 192.0.2.21\n";
    private static final String ENTITY = "/api/v1/entities/ornek-app-01";
    private static final Path CONTRACT = Path.of("..", "fixtures", "api");
    private static final String UNDO = "(kb revert --batch " + BATCH + " undoes it)";

    /**
     * {@code query} is the raw query, empty for none; {@code body} the patterns the request body
     * must hold; {@code answer} what the stub answers with 200.
     */
    record Case(
            String name,
            List<String> args,
            String method,
            String path,
            String query,
            List<Pattern> body,
            String answer,
            List<String> printed) {

        boolean answersJson() {
            return !"show".equals(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private KbCommandCases() {}

    static Stream<Case> all() {
        return Stream.of(reads(), reviews(), writes(), records(), decisions())
                .flatMap(cases -> cases);
    }

    private static Stream<Case> reads() {
        return Stream.of(
                new Case(
                        "search",
                        List.of(
                                "search", "ornek", "prod", "--type", "Server", "--env", "prod",
                                "--limit", "3"),
                        "GET",
                        "/api/v1/search",
                        "q=ornek%20prod&type=Server&env=prod&limit=3",
                        List.of(),
                        contract("search-answer.json"),
                        List.of(
                                "ornek-app-01  Server  current  ip @prod: 192.0.2.21",
                                "ornek-app-00  Server  history, archived\n",
                                "01KBTESTSIGNAL  Signal  raw  kbtest-ğhost-İ \"quoted\" \\ /",
                                "4 more not shown")),
                new Case(
                        "stale",
                        List.of("stale", "--days", "30"),
                        "GET",
                        "/api/v1/stale",
                        "days=30",
                        List.of(),
                        contract("search-answer-empty.json"),
                        List.of("nothing stale")),
                new Case(
                        "show json",
                        List.of("show", "ornek-app-01", "--json"),
                        "GET",
                        ENTITY,
                        "",
                        List.of(),
                        contract("entity-answer.json"),
                        // The ids kb fact close and kb rel close take.
                        List.of("\"id\": \"01KBTESTFACT1\"", "\"id\": \"01KBTESTREL1\"")),
                new Case(
                        "show",
                        List.of("show", "ornek-app-01"),
                        "GET",
                        ENTITY,
                        "",
                        List.of(),
                        MARKDOWN,
                        List.of(MARKDOWN)),
                new Case(
                        "cypher",
                        List.of("cypher", "MATCH (e:Entity) RETURN e.id AS id"),
                        "POST",
                        "/api/v1/cypher",
                        "",
                        List.of(ApiStub.jsonField("query", "MATCH (e:Entity) RETURN e.id AS id")),
                        contract("cypher-answer.json"),
                        List.of(
                                "id\tports\n",
                                "ornek-app-01\t[22, 8080]\n",
                                // A backslash in a cell is written \\ (H6 review, CWE-74).
                                "ornek-ğ \"q\" \\\\ /\t\n")));
    }

    private static Stream<Case> reviews() {
        return Stream.of(
                new Case(
                        "conflicts",
                        List.of("conflicts"),
                        "GET",
                        "/api/v1/conflicts",
                        "",
                        List.of(),
                        contract("conflicts-answer.json"),
                        List.of(
                                "01M61GCWC0YQ7CBPWNDRQC5FX5  ornek-app-01 ip @prod: 192.0.2.25"
                                        + " (agent, 2026-10-28T15:00:00Z); current: 192.0.2.21")),
                new Case(
                        "proposals",
                        List.of("proposals"),
                        "GET",
                        "/api/v1/proposals",
                        "",
                        List.of(),
                        contract("proposals-answer.json"),
                        List.of(
                                "01KBTESTPROP1  archive  open  by agent  target ornek-app-00\n",
                                "  ornek-app-00 kapandı\n",
                                "  \"eski\" sunucu\n",
                                "  evidence: ornek-proje README.md\n",
                                "01KBTESTPROP0  schema  rejected  by curator\n",
                                "  note: gerek yok\n",
                                "2 more not shown")),
                new Case(
                        "activity",
                        List.of(
                                "activity",
                                "--source",
                                "scan",
                                "--batch",
                                BATCH,
                                "--since",
                                "2026-11-01T00:00:00Z",
                                "--limit",
                                "5"),
                        "GET",
                        "/api/v1/activity",
                        "source=scan&batch=" + BATCH + "&since=2026-11-01T00%3A00%3A00Z&limit=5",
                        List.of(),
                        contract("activity-answer.json"),
                        List.of(
                                BATCH
                                        + "  2026-11-10T08:00:00Z  scan  opened 12, closed 0,"
                                        + " revised 0  ornek-db, ornek-proje",
                                "48 more not shown")),
                new Case(
                        "secret list",
                        List.of("secret", "list", "--prefix", "ornek", "--limit", "2"),
                        "GET",
                        "/api/v1/secrets",
                        "prefix=ornek&limit=2",
                        List.of(),
                        contract("secrets-answer.json"),
                        List.of(
                                "ornek-lb/prod/panel-admin  kb:secret/ornek-lb/prod/panel-admin"
                                        + "  value on this machine\n",
                                "ornek-proje/local/ORACLE_PASSWORD"
                                        + "  kb:secret/ornek-proje/local/ORACLE_PASSWORD"
                                        + "  no value on this machine\n",
                                "3 more not shown")));
    }

    private static Stream<Case> writes() {
        return Stream.of(
                new Case(
                        "add",
                        List.of("add", "Server", "ornek-app-01"),
                        "POST",
                        "/api/v1/entities",
                        "",
                        List.of(
                                ApiStub.jsonField("type", "Server"),
                                ApiStub.jsonField("id", "ornek-app-01")),
                        contract("write-answer.json"),
                        written()),
                edit(
                        "edit",
                        List.of(
                                "edit",
                                "ornek-app-01",
                                "--alias",
                                "app01",
                                "--alias",
                                "app-one",
                                "--summary",
                                "Uygulama \"prod\""),
                        "PATCH",
                        ENTITY,
                        List.of(
                                Pattern.compile(
                                        "\"aliases\"\\s*:\\s*\\[\\s*\"app01\"\\s*,\\s*\"app-one\"\\s*]"),
                                ApiStub.jsonField("summary", "Uygulama \\\"prod\\\"")),
                        "edited ornek-app-01"),
                edit(
                        "edit clear aliases",
                        List.of("edit", "ornek-app-01", "--clear-aliases"),
                        "PATCH",
                        ENTITY,
                        List.of(Pattern.compile("^\\{\\s*\"aliases\"\\s*:\\s*\\[\\s*]\\s*}$")),
                        "edited ornek-app-01"),
                edit(
                        "archive",
                        List.of("archive", "ornek-app-01"),
                        "POST",
                        ENTITY + "/archive",
                        List.of(),
                        "archived ornek-app-01"),
                edit(
                        "unarchive",
                        List.of("unarchive", "ornek-app-01"),
                        "POST",
                        ENTITY + "/unarchive",
                        List.of(),
                        "unarchived ornek-app-01"));
    }

    private static Stream<Case> records() {
        return Stream.of(
                new Case(
                        "fact set",
                        List.of("fact", "set", "ornek-app-01", "ip", "192.0.2.30", "--env", "prod"),
                        "POST",
                        ENTITY + "/facts",
                        "",
                        List.of(
                                ApiStub.jsonField("key", "ip"),
                                ApiStub.jsonField("value", "192.0.2.30"),
                                ApiStub.jsonField("env", "prod")),
                        contract("write-answer.json"),
                        written()),
                edit(
                        "fact close",
                        List.of("fact", "close", "01KBTESTFACT1"),
                        "POST",
                        "/api/v1/facts/01KBTESTFACT1/close",
                        List.of(),
                        "closed fact 01KBTESTFACT1"),
                new Case(
                        "rel add",
                        List.of("rel", "add", "ornek-app-01", "BEHIND", "ornek-lb"),
                        "POST",
                        ENTITY + "/relations",
                        "",
                        List.of(
                                ApiStub.jsonField("type", "BEHIND"),
                                ApiStub.jsonField("target", "ornek-lb")),
                        contract("write-answer.json"),
                        written()),
                edit(
                        "rel close",
                        List.of("rel", "close", "01KBTESTREL1"),
                        "POST",
                        "/api/v1/relations/01KBTESTREL1/close",
                        List.of(),
                        "closed relation 01KBTESTREL1"));
    }

    private static Stream<Case> decisions() {
        return Stream.of(
                edit(
                        "resolve",
                        List.of("resolve", "01M61GCWC0YQ7CBPWNDRQC5FX5", "accept-new"),
                        "POST",
                        "/api/v1/conflicts/01M61GCWC0YQ7CBPWNDRQC5FX5/resolve",
                        List.of(ApiStub.jsonField("resolution", "accept-new")),
                        "resolved conflict 01M61GCWC0YQ7CBPWNDRQC5FX5 (accept-new)"),
                edit(
                        "accept",
                        List.of("accept", "01KBTESTPROP1"),
                        "POST",
                        "/api/v1/proposals/01KBTESTPROP1/accept",
                        List.of(),
                        "accepted proposal 01KBTESTPROP1"),
                edit(
                        "reject",
                        List.of("reject", "01KBTESTPROP1", "-m", "gerek yok"),
                        "POST",
                        "/api/v1/proposals/01KBTESTPROP1/reject",
                        List.of(ApiStub.jsonField("note", "gerek yok")),
                        "rejected proposal 01KBTESTPROP1"),
                new Case(
                        "scan",
                        List.of("scan", "kbtest-project"),
                        "POST",
                        "/api/v1/scan",
                        "",
                        // Absolute: the server does not know the directory kb was started in.
                        List.of(Pattern.compile("\"path\"\\s*:\\s*\"/[^\"]*/kbtest-project\"")),
                        contract("scan-answer.json"),
                        List.of(
                                "scanned ornek-proje (git.ornek.example/ornek-grup/ornek-proje):"
                                        + " 5 files; batch "
                                        + BATCH,
                                "new entities: ornek-db\n",
                                "facts: 9 added, 2 refreshed, 1 closed, 1 conflicts\n",
                                "secrets (values stay on this machine):"
                                        + " kb:secret/ornek-proje/prod/spring.datasource.password\n",
                                "findings: ornek-db.ornek.example\n",
                                "skipped: src/x.yml:3: the value looks like a secret\n")));
    }

    /** What every write that answers write-answer.json prints. */
    private static List<String> written() {
        return List.of(
                "ornek-app-01: known entity, batch " + BATCH + " " + UNDO,
                "added: 01KBTESTFACT3\n",
                "closed: 01KBTESTFACT1\n",
                "waiting as a conflict: 01KBTESTREL1\n");
    }

    private static Case edit(
            String name,
            List<String> args,
            String method,
            String path,
            List<Pattern> body,
            String done) {
        return new Case(
                name,
                args,
                method,
                path,
                "",
                body,
                contract("edit-answer.json"),
                List.of(done + "; batch " + BATCH + " " + UNDO));
    }

    static String contract(String file) {
        try {
            return Files.readString(CONTRACT.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
