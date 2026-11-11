package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.ActivityAnswer;
import io.github.ismetdyn.agentkb.api.ConflictsAnswer;
import io.github.ismetdyn.agentkb.api.CypherAnswer;
import io.github.ismetdyn.agentkb.api.EntityAnswer;
import io.github.ismetdyn.agentkb.api.ProposalsAnswer;
import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.api.SearchAnswer;
import io.github.ismetdyn.agentkb.api.SecretsAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import java.util.List;

/**
 * The H6 records as their files under fixtures/api/ hold them (ApiContractTest); the server half
 * (kb-server H6Samples) lists the same values.
 */
final class H6Samples {

    static final String BATCH = "01KBTESTBATCH0000000000000Z";
    private static final String TRICKY = "kbtest-ğhost-İ \"quoted\" \\ /";

    static final SearchAnswer SEARCH =
            new SearchAnswer(
                    List.of(
                            new SearchAnswer.Hit(
                                    "ornek-app-01",
                                    "Server",
                                    "current",
                                    false,
                                    "ip @prod: 192.0.2.21"),
                            new SearchAnswer.Hit("ornek-app-00", "Server", "history", true, null),
                            new SearchAnswer.Hit("01KBTESTSIGNAL", "Signal", "raw", false, TRICKY)),
                    4);

    static final EntityAnswer ENTITY =
            new EntityAnswer(
                    "ornek-app-01",
                    "Server",
                    "current",
                    "Uygulama sunucusu \"prod\"",
                    List.of("app01", "ornek-app-01.ornek.example"),
                    List.of(
                            new EntityAnswer.Fact(
                                    "01KBTESTFACT1",
                                    "ip",
                                    "192.0.2.21",
                                    "prod",
                                    "user",
                                    "ok",
                                    "2026-05-10T09:00:00Z",
                                    "2026-10-01T09:00:00Z",
                                    null,
                                    null,
                                    "ornek-proje:src/main/resources/application-prod.yml:2"),
                            new EntityAnswer.Fact(
                                    "01KBTESTFACT2",
                                    "port",
                                    "8080",
                                    "default",
                                    "scan",
                                    "conflict",
                                    "2026-01-15T09:00:00Z",
                                    "2026-01-15T09:00:00Z",
                                    "2026-05-10T09:00:00Z",
                                    "ornek-proje",
                                    null)),
                    List.of(
                            new EntityAnswer.Relation(
                                    "01KBTESTREL1",
                                    "BEHIND",
                                    true,
                                    "ornek-lb",
                                    "prod",
                                    "user",
                                    "2026-05-10T09:00:00Z",
                                    "2026-05-10T09:00:00Z",
                                    null),
                            new EntityAnswer.Relation(
                                    null,
                                    "RUNS",
                                    false,
                                    "ornek-proje",
                                    null,
                                    "agent",
                                    "2026-01-15T09:00:00Z",
                                    "2026-01-15T09:00:00Z",
                                    "2026-05-10T09:00:00Z")),
                    List.of(
                            new EntityAnswer.Secret(
                                    "ornek-app-01/prod/deploy-ssh-key",
                                    "deploy-ssh-key",
                                    "prod",
                                    "kb:secret/ornek-app-01/prod/deploy-ssh-key")));

    static final EntityAnswer BARE_ENTITY =
            new EntityAnswer(
                    "kbtest-bare",
                    "Topic",
                    "archived",
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());

    static final WriteAnswer WRITE =
            new WriteAnswer(
                    "ornek-app-01",
                    false,
                    BATCH,
                    List.of("01KBTESTFACT3"),
                    List.of(),
                    List.of("01KBTESTFACT1"),
                    List.of("01KBTESTREL1"));

    static final CypherAnswer CYPHER =
            new CypherAnswer(
                    List.of("id", "ports"),
                    List.of(
                            new CypherAnswer.Row(List.of("ornek-app-01", "[22, 8080]")),
                            new CypherAnswer.Row(List.of("ornek-ğ \"q\" \\ /", ""))),
                    true);

    static final ConflictsAnswer CONFLICTS =
            new ConflictsAnswer(
                    List.of(
                            new ConflictsAnswer.Conflict(
                                    "01M61GCWC0YQ7CBPWNDRQC5FX5",
                                    "ornek-app-01",
                                    "ip",
                                    "prod",
                                    "192.0.2.25",
                                    "agent",
                                    "2026-10-28T15:00:00Z",
                                    List.of("192.0.2.21"))),
                    0);

    static final ProposalsAnswer PROPOSALS =
            new ProposalsAnswer(
                    List.of(
                            new ProposalsAnswer.Proposal(
                                    "01KBTESTPROP1",
                                    "archive",
                                    "open",
                                    "agent",
                                    "ornek-app-00",
                                    "ornek-app-00 kapandı\n\n\"eski\" sunucu",
                                    "ornek-proje README.md",
                                    null),
                            new ProposalsAnswer.Proposal(
                                    "01KBTESTPROP0",
                                    "schema",
                                    "rejected",
                                    "curator",
                                    null,
                                    "name: rack",
                                    null,
                                    "gerek yok")),
                    2);

    static final ActivityAnswer ACTIVITY =
            new ActivityAnswer(
                    List.of(
                            new ActivityAnswer.Batch(
                                    BATCH,
                                    "2026-11-10T08:00:00Z",
                                    List.of("scan"),
                                    12,
                                    0,
                                    0,
                                    List.of("ornek-db", "ornek-proje")),
                            new ActivityAnswer.Batch(
                                    "01KBTESTBATCH0000000000000Y",
                                    "2026-11-09T08:00:00Z",
                                    List.of("user"),
                                    0,
                                    1,
                                    1,
                                    List.of("ornek-app-01"))),
                    48);

    static final SecretsAnswer SECRETS =
            new SecretsAnswer(
                    List.of(
                            new SecretsAnswer.Secret(
                                    "ornek-lb/prod/panel-admin",
                                    "panel-admin",
                                    "prod",
                                    "kb:secret/ornek-lb/prod/panel-admin",
                                    true),
                            new SecretsAnswer.Secret(
                                    "ornek-proje/local/ORACLE_PASSWORD",
                                    "ORACLE_PASSWORD",
                                    "local",
                                    "kb:secret/ornek-proje/local/ORACLE_PASSWORD",
                                    false)),
                    3);

    static final ScanAnswer SCAN =
            new ScanAnswer(
                    "ornek-proje",
                    "git.ornek.example/ornek-grup/ornek-proje",
                    BATCH,
                    5,
                    List.of("ornek-db"),
                    9,
                    2,
                    1,
                    1,
                    List.of("kb:secret/ornek-proje/prod/spring.datasource.password"),
                    List.of("ornek-db.ornek.example"),
                    List.of("src/x.yml:3: the value looks like a secret"));

    private H6Samples() {}
}
