package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.BatchAnswer;
import io.github.ismetdyn.agentkb.api.BatchAnswer.Change;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.util.List;

/**
 * The records of the TUI/GUI reads (schema, entity list, batch detail) as their files under
 * fixtures/api/ hold them (ApiContractTest); the client half lists the same values.
 */
final class H8Samples {

    private static final String APP = "ornek-app-01";
    private static final String USER = "user";
    private static final String OPENED = "opened";

    static final SchemaAnswer SCHEMA =
            new SchemaAnswer(
                    List.of(
                            new SchemaAnswer.EntityType("server", "Server", true),
                            new SchemaAnswer.EntityType("topic", "Topic", false)),
                    List.of(
                            new SchemaAnswer.FactKey(
                                    "port", true, "port-spec", List.of(), null, true),
                            new SchemaAnswer.FactKey(
                                    "engine",
                                    false,
                                    null,
                                    List.of("oracle", "postgresql"),
                                    null,
                                    false),
                            new SchemaAnswer.FactKey("role", true, null, List.of(), "text", false)),
                    List.of(
                            new SchemaAnswer.RelationType(
                                    "RUNS",
                                    "Bu sunucu/küme şu servisi/uygulamayı çalıştırır",
                                    List.of("HOSTS")),
                            new SchemaAnswer.RelationType(
                                    "PART_OF", "Daha büyük bir bütünün parçası \"İ\"", List.of())));

    static final EntityListAnswer ENTITIES =
            new EntityListAnswer(
                    List.of(
                            new EntityListAnswer.Entity(
                                    APP,
                                    "Server",
                                    "current",
                                    "Uygulama sunucusu \"prod\"",
                                    "2026-11-10T08:00:00Z"),
                            new EntityListAnswer.Entity(
                                    "ornek-proje", "Project", "observed", null, ""),
                            new EntityListAnswer.Entity(
                                    "ornek-app-00",
                                    "Server",
                                    "archived",
                                    "kbtest-ğhost-İ",
                                    "2026-11-09T08:00:00Z")),
                    12);

    static final BatchAnswer BATCH =
            new BatchAnswer(
                    H6Samples.BATCH,
                    "2026-11-10T08:00:00Z",
                    List.of(USER),
                    false,
                    List.of("01KBTESTBATCH00000000000010"),
                    List.of(
                            new Change(
                                    OPENED,
                                    "entity",
                                    APP,
                                    APP,
                                    "Server",
                                    null,
                                    "Uygulama sunucusu",
                                    USER),
                            new Change(
                                    OPENED,
                                    "fact",
                                    "01KBTESTFACT1",
                                    APP,
                                    "ip",
                                    "prod",
                                    "192.0.2.21",
                                    USER),
                            new Change(
                                    OPENED,
                                    "relation",
                                    "01KBTESTREL1",
                                    APP,
                                    "BEHIND",
                                    "prod",
                                    "ornek-lb",
                                    USER),
                            new Change(
                                    OPENED,
                                    "secret",
                                    APP + "/prod/db-password",
                                    APP,
                                    "db-password",
                                    "prod",
                                    null,
                                    USER),
                            new Change(
                                    "closed",
                                    "fact",
                                    "01KBTESTFACT0",
                                    APP,
                                    "ip",
                                    "prod",
                                    "kbtest-ğhost-İ \"quoted\" \\ /",
                                    USER),
                            new Change(
                                    "revised",
                                    "entity",
                                    APP,
                                    APP,
                                    "aliases, summary",
                                    null,
                                    null,
                                    USER)),
                    0);

    private H8Samples() {}
}
