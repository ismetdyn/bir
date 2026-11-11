package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.BatchAnswer;
import io.github.ismetdyn.agentkb.api.EntityListAnswer;
import io.github.ismetdyn.agentkb.api.SchemaAnswer;
import java.util.List;

/** The records the TUI reads (plan.md H8a) as their files under fixtures/api/ hold them. */
final class H8Samples {

    private static final String TRICKY = "kbtest-ğhost-İ";

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

    static final EntityListAnswer ENTITY_LIST =
            new EntityListAnswer(
                    List.of(
                            new EntityListAnswer.Entity(
                                    "ornek-app-01",
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
                                    TRICKY,
                                    "2026-11-09T08:00:00Z")),
                    12);

    static final BatchAnswer BATCH =
            new BatchAnswer(
                    H6Samples.BATCH,
                    "2026-11-10T08:00:00Z",
                    List.of("user"),
                    false,
                    List.of("01KBTESTBATCH00000000000010"),
                    List.of(
                            change(
                                    "opened",
                                    "entity",
                                    "ornek-app-01",
                                    "Server",
                                    null,
                                    "Uygulama sunucusu"),
                            change("opened", "fact", "01KBTESTFACT1", "ip", "prod", "192.0.2.21"),
                            change(
                                    "opened",
                                    "relation",
                                    "01KBTESTREL1",
                                    "BEHIND",
                                    "prod",
                                    "ornek-lb"),
                            change(
                                    "opened",
                                    "secret",
                                    "ornek-app-01/prod/db-password",
                                    "db-password",
                                    "prod",
                                    null),
                            change(
                                    "closed",
                                    "fact",
                                    "01KBTESTFACT0",
                                    "ip",
                                    "prod",
                                    TRICKY + " \"quoted\" \\ /"),
                            change(
                                    "revised",
                                    "entity",
                                    "ornek-app-01",
                                    "aliases, summary",
                                    null,
                                    null)),
                    0);

    private H8Samples() {}

    private static BatchAnswer.Change change(
            String change, String kind, String id, String key, String env, String value) {
        return new BatchAnswer.Change(change, kind, id, "ornek-app-01", key, env, value, "user");
    }
}
